package org.apache.flume.sink.elasticsearch.high;

/**
 * @author dalizu on 2019/2/13.
 * @version v1.0
 * @desc 索引字段
 */
public class EventIndex {


    private String serviceName;//系统类型

    private String body;//日志内容

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public EventIndex(String serviceName, String body) {
        this.serviceName = serviceName;
        this.body = body;
    }
}
