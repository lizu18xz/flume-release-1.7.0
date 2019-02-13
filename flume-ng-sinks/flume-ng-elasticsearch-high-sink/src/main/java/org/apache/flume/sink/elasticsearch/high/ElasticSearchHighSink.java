package org.apache.flume.sink.elasticsearch.high;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.apache.http.HttpHost;
import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import static org.apache.flume.sink.elasticsearch.high.ElasticSearchHighSinkConstants.BATCH_SIZE;
import static org.apache.flume.sink.elasticsearch.high.ElasticSearchHighSinkConstants.DEFAULT_PORT;
import static org.apache.flume.sink.elasticsearch.high.ElasticSearchHighSinkConstants.INDEX_NAME;
/**
 * @author dalizu on 2019/2/13.
 * @version v1.0
 * @desc 高版本的ES Sink  version 6.x
 * <p>
 * sink类关系
 * 入口:SinkRunner--->start()
 * SinkProcessor -- start,process,stop
 */
public class ElasticSearchHighSink extends AbstractSink implements Configurable {

    private static final Logger logger = LoggerFactory
            .getLogger(ElasticSearchHighSink.class);

    private static final Charset charset = Charset.defaultCharset();

    private static ObjectMapper objectMapper = new ObjectMapper();

    private final CounterGroup counterGroup = new CounterGroup();

    private String[] serverAddresses = null;

    private static final int defaultBatchSize = 100;
    private int batchSize = defaultBatchSize;

    private String indexName = ElasticSearchHighSinkConstants.DEFAULT_INDEX_NAME;

    private SinkCounter sinkCounter;


    private RestHighLevelClient client;


    //执行event的具体操作
    @Override
    public Status process() throws EventDeliveryException {
        logger.info("process elasticsearch sink......");

        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction txn = channel.getTransaction();

        List<Event> events = Lists.newArrayList();

        try {
            txn.begin();
            int count;
            for (count = 0; count < batchSize; ++count) {
                //从channel中获取元素,实际是event = queue.poll();如果为空则会抛出异常,会被捕获处理返回null
                Event event = channel.take();

                if (event == null) {
                    break;
                }
                //进行批处理到ES
                events.add(event);
            }

            //当达到设置的批次后进行提交
            if (count <= 0) {
                sinkCounter.incrementBatchEmptyCount();
                counterGroup.incrementAndGet("channel.underflow");
                status = Status.BACKOFF;
            } else {
                if (count < batchSize) {
                    sinkCounter.incrementBatchUnderflowCount();
                    status = Status.BACKOFF;
                } else {
                    sinkCounter.incrementBatchCompleteCount();
                }

                sinkCounter.addToEventDrainAttemptCount(count);
                //提交当前批次到ES
                bulkExecute(events);
            }
            txn.commit();
            sinkCounter.addToEventDrainSuccessCount(count);
            counterGroup.incrementAndGet("transaction.success");
        } catch (Throwable ex) {
            try {
                txn.rollback();
                counterGroup.incrementAndGet("transaction.rollback");
            } catch (Exception ex2) {
                logger.error(
                        "Exception in rollback. Rollback might not have been successful.",
                        ex2);
            }

            if (ex instanceof Error || ex instanceof RuntimeException) {
                logger.error("Failed to commit transaction. Transaction rolled back.",
                        ex);
                Throwables.propagate(ex);
            } else {
                logger.error("Failed to commit transaction. Transaction rolled back.",
                        ex);
                throw new EventDeliveryException(
                        "Failed to commit transaction. Transaction rolled back.", ex);
            }
        } finally {
            txn.close();
        }
        return status;
    }

    //初始化执行获取配置信息
    @Override
    public void configure(Context context) {
        logger.info("configure elasticsearch......");

        //获取配置信息

        if (StringUtils.isNotBlank(context.getString(ElasticSearchHighSinkConstants.HOSTNAMES))) {
            serverAddresses = StringUtils.deleteWhitespace(
                    context.getString(ElasticSearchHighSinkConstants.HOSTNAMES)).split(",");
        }
        Preconditions.checkState(serverAddresses != null
                && serverAddresses.length > 0, "Missing Param:" + ElasticSearchHighSinkConstants.HOSTNAMES);


        if (StringUtils.isNotBlank(context.getString(INDEX_NAME))) {
            this.indexName = context.getString(INDEX_NAME);
        }

        if (StringUtils.isNotBlank(context.getString(BATCH_SIZE))) {
            this.batchSize = Integer.parseInt(context.getString(BATCH_SIZE));
        }

        logger.info("获取到ES配置信息,address:"+StringUtils.join(serverAddresses)+",index:"+indexName+",batchSize:"+batchSize);

        if (sinkCounter == null) {
            sinkCounter = new SinkCounter(getName());
        }

        Preconditions.checkState(StringUtils.isNotBlank(indexName),
                "Missing Param:" + INDEX_NAME);
        Preconditions.checkState(batchSize >= 1, BATCH_SIZE
                + " must be greater than 0");
    }


    //启动sink的一些配置
    @Override
    public synchronized void start() {
        logger.info("start elasticsearch sink......");

        HttpHost[] httpHosts = new HttpHost[serverAddresses.length];

        for (int i = 0; i < serverAddresses.length; i++) {
            String[] hostPort = serverAddresses[i].trim().split(":");
            String host = hostPort[0].trim();
            int port = hostPort.length == 2 ? Integer.parseInt(hostPort[1].trim())
                    : DEFAULT_PORT;
            logger.info("elasticsearch host:{},port:{}", host, port);
            httpHosts[i] = new HttpHost(host, port, "http");
        }

        client = new RestHighLevelClient(
                RestClient.builder(httpHosts));

        sinkCounter.start();
        super.start();
    }

    //关闭资源
    @Override
    public synchronized void stop() {
        logger.info("stop elasticsearch sink......");
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        sinkCounter.stop();
        super.stop();
    }


    //ES批处理的方法
    public void bulkExecute(List<Event> events) throws Exception {

        //批量插入数据
        BulkRequest request = new BulkRequest();

        for (Event event : events) {
            //解析event
            String body = new String(event.getBody(), charset);
            EventIndex eventIndex = null;

            if (body.contains("INFO") == true || body.contains("WARN") == true || body.contains("ERROR") == true ||
                    body.contains("DEBUG") == true) {
                //serviceName , body
                eventIndex = new EventIndex(body.substring(0, body.indexOf(" ")),
                        body.substring(body.indexOf(" ") + 1));

            } else {
                //默认系统名称是sys
                eventIndex = new EventIndex("sys", body);
            }

            request.add(new IndexRequest(indexName, indexName)
                    .source(objectMapper.writeValueAsString(eventIndex), XContentType.JSON));
        }

        BulkResponse bulkResponse = client.bulk(request, RequestOptions.DEFAULT);
        //The Bulk response provides a method to quickly check if one or more operation has failed:
        if (bulkResponse.hasFailures()) {
            logger.info("all success");
        }
        TimeValue took = bulkResponse.getTook();
        logger.info("[批量新增花费的毫秒]:" + took + "," + took.getMillis() + "," + took.getSeconds());
        //所有操作结果进行迭代
        /*for (BulkItemResponse bulkItemResponse : bulkResponse) {
               if (bulkItemResponse.isFailed()) {
                   BulkItemResponse.Failure failure = bulkItemResponse.getFailure();
               }
         }*/
    }


    public static void main(String[] args) {

        String log = "weblog 2019-02-12 10:33:26 [com.faya.data.controller.LoginController]-[INFO] 用户登陆入参：userId = 10";
        System.out.println(log.substring(0, log.indexOf(" ")));
        System.out.println(log.substring(log.indexOf(" ") + 1));
    }

}
