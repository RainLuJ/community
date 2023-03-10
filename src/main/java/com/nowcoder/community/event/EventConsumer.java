package com.nowcoder.community.event;

import com.alibaba.fastjson.JSONObject;
import com.nowcoder.community.entity.Event;
import com.nowcoder.community.entity.Message;
import com.nowcoder.community.service.DiscussPostService;
import com.nowcoder.community.service.ElasticsearchService;
import com.nowcoder.community.service.MessageService;
import com.nowcoder.community.util.CommunityConstant;
import com.nowcoder.community.util.CommunityUtil;
import com.qiniu.common.QiniuException;
import com.qiniu.common.Zone;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.qiniu.util.StringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * @author rainlu
 * @version 1.0.0
 * @Description 事件消费者
 */

@Component
public class EventConsumer implements CommunityConstant {

    private static final Logger logger = LoggerFactory.getLogger(EventConsumer.class);

    @Autowired
    private MessageService messageService;

    @Autowired
    private DiscussPostService discussPostService;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Value("${wk.image.command}")
    private String wkImageCommand;

    @Value("${wk.image.storage}")
    private String wkImageStorage;

    @Value("${qiniu.key.access}")
    private String accessKey;

    @Value("${qiniu.key.secret}")
    private String secretKey;

    @Value("${qiniu.bucket.share.name}")
    private String shareBucketName;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @RabbitListener(queues = {TOPIC_COMMENT, TOPIC_FOLLOW, TOPIC_LIKE})
    public void handleCommentMessage(org.springframework.amqp.core.Message messag) {
        String msg = new String(messag.getBody());
        if ("".equals(msg)) {
            logger.error("消息的内容为空");
            return;
        }
        Event event = JSONObject.parseObject(msg, Event.class);
        if (event == null) {
            logger.error("消息格式错误");
        } else {
            // 发送站内通知
            /* 系统通知也是消息，只是FromId都是1。所以也得记录在Message表中 */
            Message message = new Message();
            message.setFromId(SYSTEM_USER_ID);
            message.setToId(event.getEntityUserId());
            // 此处存的是主题：如果存“fromID_toID”，就一直都是系统给用户发送通知，显得冗余
            message.setConversationId(event.getTopic());
            message.setCreateTime(new Date());

            Map<String, Object> content = new HashMap<>();
            content.put("userId", event.getUserId());
            content.put("entityType", event.getEntityType());
            content.put("entityId", event.getEntityId());

            // 处理map中内容
            if (!event.getData().isEmpty()) {
                content.putAll(event.getData());
            }

            message.setContent(JSONObject.toJSONString(content));
            messageService.addMessage(message);
        }
    }

    /** 消费发帖事件：
     * 使用消息队列是为了【异步更新】ES中的数据
     */
    @RabbitListener(queues = {TOPIC_PUBLISH})
    public void handlePublishMessage(org.springframework.amqp.core.Message message) {
        String msg = new String(message.getBody());
        if ("".equals(msg)) {
            logger.error("消息的内容为空");
            return;
        }
        Event event = JSONObject.parseObject(msg, Event.class);
        if (event == null) {
            logger.error("消息格式错误");
        } else {
            /*DiscussPost post = discussPostService.findDiscussPostById(event.getEntityId());
            elasticsearchService.saveDiscussPost(post);*/
        }
    }

    // 消费分享事件
    @RabbitListener(queues = TOPIC_SHARE)
    public void handleShareMessage(org.springframework.amqp.core.Message message) {
        String msg = new String(message.getBody());
        if ("".equals(msg)) {
            logger.error("消息的内容为空");
            return;
        }

        Event event = JSONObject.parseObject(msg, Event.class);
        if (event == null) {
            logger.error("消息格式错误!");
            return;
        }

        String htmlUrl = (String) event.getData().get("htmlUrl");
        String fileName = (String) event.getData().get("fileName");
        String suffix = (String) event.getData().get("suffix");   // .png

        String cmd = wkImageCommand + " --quality 75 "
                + htmlUrl + " " + wkImageStorage + "/" + fileName + suffix;
        try {
            // 不需要导包，直接使用Java原生的API执行本地命令
            Runtime.getRuntime().exec(cmd);
            logger.info("生成长图成功: " + cmd);
        } catch (IOException e) {
            logger.error("生成长图失败: " + e.getMessage());
        }

        // 启动定时器，监视该图片，一旦生成了，上传云服务器
        // 如果超时(30s),或者上传失败(>=3次) 取消任务.返回值future可以取消task
        UploadTask task = new UploadTask(fileName, suffix);
        Future<?> future = taskScheduler.scheduleAtFixedRate(task, 500);
        task.setFuture(future);
    }

    class UploadTask implements Runnable {
        // 文件名称
        private String fileName;
        // 文件后缀
        private String suffix;   // .png
        // 启动任务的返回值
        private Future<?> future;
        // 开始时间
        private long startTime;
        // 上传次数
        private int uploadTimes;

        public UploadTask(String fileName, String suffix) {
            this.fileName = fileName;
            this.suffix = suffix;
            this.startTime = System.currentTimeMillis();
        }

        public void setFuture(Future<?> future) {
            this.future = future;
        }

        @Override
        public void run() {

            // 生成失败
            if (System.currentTimeMillis() - startTime > 30000) {
                logger.error("执行时间过长,终止任务:" + fileName);
                future.cancel(true);
                return;
            }

            // 上传失败，一般是上传七牛云失败
            if (uploadTimes >= 3) {
                logger.error("上传次数过多,终止任务:" + fileName);
                future.cancel(true);
                return;
            }

            String path = wkImageStorage + "/" + fileName + suffix;
            File file = new File(path);
            if (file.exists()) {
                logger.info(String.format("开始第%d次上传[%s].", ++uploadTimes, fileName));
                // 设置响应信息
                StringMap policy = new StringMap();
                policy.put("returnBody", CommunityUtil.getJSONString(0));
                // 生成上传凭证
                Auth auth = Auth.create(accessKey, secretKey);
                String uploadToken = auth.uploadToken(shareBucketName, fileName, 3600, policy);
                // 指定上传机房
                UploadManager manager = new UploadManager(new Configuration(Zone.zone2()));
                try {
                    // 开始上传图片
                    Response response = manager.put(
                            path, fileName, uploadToken, null, "image/" + suffix.substring(1), false);
                    // 处理响应结果
                    JSONObject json = JSONObject.parseObject(response.bodyString());
                    if (json == null || json.get("code") == null || !json.get("code").toString().equals("0")) {
                        logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                    } else {
                        logger.info(String.format("第%d次上传成功[%s].", uploadTimes, fileName));
                        future.cancel(true);
                    }
                } catch (QiniuException e) {
                    logger.info(String.format("第%d次上传失败[%s].", uploadTimes, fileName));
                }
            } else {
                logger.info("等待图片生成[" + fileName + "].");
            }
        }
    }
}
