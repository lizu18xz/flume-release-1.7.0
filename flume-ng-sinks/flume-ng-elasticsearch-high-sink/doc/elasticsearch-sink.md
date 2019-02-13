
- 开发一个高版本的ES-Sink,版本为 6.4.3


### 配置文件
`````text

agent.sources = elasticsearch_sources01
agent.channels = elasticsearch_channel01
agent.sinks = elasticsearch_sink01

agent.sources.elasticsearch_sources01.type = avro
agent.sources.elasticsearch_sources01.bind = 192.168.88.130
agent.sources.elasticsearch_sources01.port = 4545
agent.sources.elasticsearch_sources01.channels = elasticsearch_channel01

agent.channels.elasticsearch_channel01.type = memory
agent.channels.elasticsearch_channel01.capacity = 1000000
agent.channels.elasticsearch_channel01.transactionCapacity = 6000

agent.sinks.elasticsearch_sink01.type = elasticsearch_high
agent.sinks.elasticsearch_sink01.hostNames = 192.168.88.130:9200
agent.sinks.elasticsearch_sink01.indexName = flume_yz
agent.sinks.elasticsearch_sink01.batchSize = 500
agent.sinks.elasticsearch_sink01.channel = elasticsearch_channel01


`````