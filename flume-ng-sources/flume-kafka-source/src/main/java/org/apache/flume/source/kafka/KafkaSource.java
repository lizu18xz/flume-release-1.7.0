/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flume.source.kafka;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import kafka.cluster.BrokerEndPoint;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.conf.LogPrivacyUtil;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.kafka.KafkaSourceCounter;
import org.apache.flume.source.AbstractPollableSource;
import org.apache.flume.source.avro.AvroFlumeEvent;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.protocol.SecurityProtocol;
import org.apache.kafka.common.security.JaasUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import scala.Option;

import static org.apache.flume.source.kafka.KafkaSourceConstants.*;
import static scala.collection.JavaConverters.asJavaListConverter;

/**
 * A Source for Kafka which reads messages from kafka topics.
 *
 * <tt>kafka.bootstrap.servers: </tt> A comma separated list of host:port pairs
 * to use for establishing the initial connection to the Kafka cluster.
 * For example host1:port1,host2:port2,...
 * <b>Required</b> for kafka.
 * <p>
 * <tt>kafka.consumer.group.id: </tt> the group ID of consumer group. <b>Required</b>
 * <p>
 * <tt>kafka.topics: </tt> the topic list separated by commas to consume messages from.
 * <b>Required</b>
 * <p>
 * <tt>maxBatchSize: </tt> Maximum number of messages written to Channel in one
 * batch. Default: 1000
 * <p>
 * <tt>maxBatchDurationMillis: </tt> Maximum number of milliseconds before a
 * batch (of any size) will be written to a channel. Default: 1000
 * <p>
 * <tt>kafka.consumer.*: </tt> Any property starting with "kafka.consumer" will be
 * passed to the kafka consumer So you can use any configuration supported by Kafka 0.9.0.X
 * <tt>useFlumeEventFormat: </tt> Reads events from Kafka Topic as an Avro FlumeEvent. Used
 * in conjunction with useFlumeEventFormat (Kafka Sink) or parseAsFlumeEvent (Kafka Channel)
 * <p>
 *
 *  Kafka Source保证至少一次消息检索策略。
 *  Kafka source guarantees at least once strategy of messages retrieval.
 *  The Kafka Source overrides two Kafka consumer parameters:
 *  auto.commit.enable is set to “false” by the source and every batch is committed.
 *  Modification of these parameters is not recommended.
 */
public class KafkaSource extends AbstractPollableSource
        implements Configurable {
  private static final Logger log = LoggerFactory.getLogger(KafkaSource.class);

  // Constants used only for offset migration zookeeper connections 仅用于偏移 迁移 ZooKeeper连接
  private static final int ZK_SESSION_TIMEOUT = 30000;
  private static final int ZK_CONNECTION_TIMEOUT = 30000;

  private Context context;
  private Properties kafkaProps;
  private KafkaSourceCounter counter;
  private KafkaConsumer<String, byte[]> consumer;
  private Iterator<ConsumerRecord<String, byte[]>> it;

  private final List<Event> eventList = new ArrayList<Event>();
  private Map<TopicPartition, OffsetAndMetadata> tpAndOffsetMetadata;
  private AtomicBoolean rebalanceFlag;

  private Map<String, String> headers;

  private Optional<SpecificDatumReader<AvroFlumeEvent>> reader = Optional.absent();
  private BinaryDecoder decoder = null;

  private boolean useAvroEventFormat;

  private int batchUpperLimit;
  private int maxBatchDurationMillis;

  private Subscriber subscriber;

  private String zookeeperConnect;
  private String bootstrapServers;
  private String groupId = DEFAULT_GROUP_ID;
  private boolean migrateZookeeperOffsets = DEFAULT_MIGRATE_ZOOKEEPER_OFFSETS;

  /**
   * This class is a helper to subscribe for topics by using
   * different strategies
   */
  public abstract class Subscriber<T> {
    public abstract void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener);

    public T get() {
      return null;
    }
  }

  private class TopicListSubscriber extends Subscriber<List<String>> {
    private List<String> topicList;

    public TopicListSubscriber(String commaSeparatedTopics) {
      this.topicList = Arrays.asList(commaSeparatedTopics.split("^\\s+|\\s*,\\s*|\\s+$"));
    }

    @Override
    public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
      consumer.subscribe(topicList, listener);
    }

    @Override
    public List<String> get() {
      return topicList;
    }
  }

  private class PatternSubscriber extends Subscriber<Pattern> {
    private Pattern pattern;

    public PatternSubscriber(String regex) {
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public void subscribe(KafkaConsumer<?, ?> consumer, SourceRebalanceListener listener) {
      consumer.subscribe(pattern, listener);
    }

    @Override
    public Pattern get() {
      return pattern;
    }
  }


  @Override
  protected Status doProcess() throws EventDeliveryException {
    final String batchUUID = UUID.randomUUID().toString();
    byte[] kafkaMessage;
    String kafkaKey;
    Event event;
    byte[] eventBody;

    try {
      // prepare time variables for new batch
      final long nanoBatchStartTime = System.nanoTime();
      final long batchStartTime = System.currentTimeMillis();

      //批处理等待的最大毫秒数
      final long maxBatchEndTime = System.currentTimeMillis() + maxBatchDurationMillis;

      //如果获取到evevt的数量小于设置的批次,则继续获取,否则提交当前批次到channel,然后清空eventList
      while (eventList.size() < batchUpperLimit &&
              System.currentTimeMillis() < maxBatchEndTime) {

        //拉取一批次处理
        if (it == null || !it.hasNext()) {
          // Obtaining new records
          // Poll time is remainder time for current batch. 初始化消费者
          ConsumerRecords<String, byte[]> records = consumer.poll(
                  Math.max(0, maxBatchEndTime - System.currentTimeMillis()));
          it = records.iterator();

          // this flag is set to true in a callback when some partitions are revoked.
          // If there are any records we commit them.
          //这里说明进行了重新分配,遇到这种情况立即跳出去处理(放到 Channel 并向 kafka 确认)

          if (rebalanceFlag.compareAndSet(true, false)) {
            //invalidAll();
            break;
          }

          // check records after poll
          if (!it.hasNext()) {
            if (log.isDebugEnabled()) {
              counter.incrementKafkaEmptyCount();
              log.debug("Returning with backoff. No more data to read");
            }
            // batch time exceeded
            break;
          }
        }

        // get next message
        ConsumerRecord<String, byte[]> message = it.next();
        kafkaKey = message.key();
        kafkaMessage = message.value();

        //是否使用这种格式
        if (useAvroEventFormat) {
          //Assume the event is in Avro format using the AvroFlumeEvent schema
          //Will need to catch the exception if it is not
          ByteArrayInputStream in =
                  new ByteArrayInputStream(message.value());
          decoder = DecoderFactory.get().directBinaryDecoder(in, decoder);
          if (!reader.isPresent()) {
            reader = Optional.of(
                    new SpecificDatumReader<AvroFlumeEvent>(AvroFlumeEvent.class));
          }
          //This may throw an exception but it will be caught by the
          //exception handler below and logged at error
          AvroFlumeEvent avroevent = reader.get().read(null, decoder);

          eventBody = avroevent.getBody().array();
          headers = toStringMap(avroevent.getHeaders());

        } else {

          //不使用的时候,可以直接获取到消息内容

          eventBody = message.value();
          headers.clear();
          headers = new HashMap<String, String>(4);
        }

        // Add headers to event (timestamp, topic, partition, key) only if they don't exist
        // 设置消息信息到event里面
        if (!headers.containsKey(KafkaSourceConstants.TIMESTAMP_HEADER)) {
          headers.put(KafkaSourceConstants.TIMESTAMP_HEADER,
              String.valueOf(System.currentTimeMillis()));
        }
        if (!headers.containsKey(KafkaSourceConstants.TOPIC_HEADER)) {
          headers.put(KafkaSourceConstants.TOPIC_HEADER, message.topic());
        }
        if (!headers.containsKey(KafkaSourceConstants.PARTITION_HEADER)) {
          headers.put(KafkaSourceConstants.PARTITION_HEADER,
              String.valueOf(message.partition()));
        }

        if (kafkaKey != null) {
          headers.put(KafkaSourceConstants.KEY_HEADER, kafkaKey);
        }

        if (log.isTraceEnabled()) {
          if (LogPrivacyUtil.allowLogRawData()) {
            log.trace("Topic: {} Partition: {} Message: {}", new String[]{
                message.topic(),
                String.valueOf(message.partition()),
                new String(eventBody)
            });
          } else {
            log.trace("Topic: {} Partition: {} Message arrived.",
                message.topic(),
                String.valueOf(message.partition()));
          }
        }

        //组装消息信息到event,加入到批次里面
        event = EventBuilder.withBody(eventBody, headers);
        eventList.add(event);

        if (log.isDebugEnabled()) {
          log.debug("Waited: {} ", System.currentTimeMillis() - batchStartTime);
          log.debug("Event #: {}", eventList.size());
        }

        // For each partition store next offset that is going to be read.
        // 对于每个分区，存储将要读取的下一个偏移量
        tpAndOffsetMetadata.put(new TopicPartition(message.topic(), message.partition()),
                new OffsetAndMetadata(message.offset() + 1, batchUUID));
      }

      //开始提交一批次到channel中,提交偏移量到kafka
      if (eventList.size() > 0) {
        counter.addToKafkaEventGetTimer((System.nanoTime() - nanoBatchStartTime) / (1000 * 1000));
        counter.addToEventReceivedCount((long) eventList.size());
        //提交一批次到channel中,需要时间

        //注意:如果新增了一个消费者,这里耗时过久的话,另一个消费者已经启动,可能会造成还没有提交到kafka,导致这些没有被提交的数据会被重复消费

        getChannelProcessor().processEventBatch(eventList);
        counter.addToEventAcceptedCount(eventList.size());
        if (log.isDebugEnabled()) {
          log.debug("Wrote {} events to channel", eventList.size());
        }
        eventList.clear();

        //提交偏移量到kafka,同步提交 CommitFailedException
        if (!tpAndOffsetMetadata.isEmpty()) {
          long commitStartTime = System.nanoTime();
          consumer.commitSync(tpAndOffsetMetadata);
          long commitEndTime = System.nanoTime();
          counter.addToKafkaCommitTimer((commitEndTime - commitStartTime) / (1000 * 1000));
          tpAndOffsetMetadata.clear();
        }
        return Status.READY;
      }

      return Status.BACKOFF;
    } catch (Exception e) {
      log.error("KafkaSource EXCEPTION, {}", e);
      return Status.BACKOFF;
    }
  }

  /**
   * 解决 重复消费数据的问题
   * 在 Kafka 回收，分配过程 (rebalance) 中，已经接收到 Flume 端但还未被 Flume 放到 Channel 的 Records
   * (自然也就没有向 Kafka 确认) 就被Kafka 认为未被消费；特别是属于将来被分配给其他 Flume 实例的 Partition 上的 Records，
   * 就会再次被新的 Flume 实例消费，造成重复加载
   *
   * 原理:
   * 把当前获取的消费的数据,(因为还没有提交到channel也没有提交到kafka)直接清空,重新设置kafka的seek,意思是在重新读取一遍
   * 参考:
   * https://github.com/hejiang2000/flume/blob/hejiang-kafka-source/flume-ng-sources/flume-kafka-source/src/main/java/org/apache/flume/source/kafka/KafkaSource.java
   */
  private void invalidAll() {
    // invalidate remaining records
    it = null;
    // and drop processed events
    eventList.clear();
    tpAndOffsetMetadata.clear();
    // and seek to committed offsets
    for (Map.Entry<String, List<PartitionInfo>> es : consumer.listTopics().entrySet()) {
      for (PartitionInfo pi : es.getValue()) {
        TopicPartition tp = new TopicPartition(pi.topic(), pi.partition());
        try {
          OffsetAndMetadata oam = consumer.committed(tp);
          if (oam != null) {
            consumer.seek(tp, oam.offset());
          }
        } catch (Exception e) {
          // log.warn("ignore seeking exception, {}", e);
        }
      }
    }
  }

  /**
   * We configure the source and generate properties for the Kafka Consumer
   *
   * Kafka Consumer properties are generated as follows:
   * 1. Generate a properties object with some static defaults that can be
   * overridden if corresponding properties are specified
   * 2. We add the configuration users added for Kafka (parameters starting
   * with kafka.consumer and must be valid Kafka Consumer properties
   * 3. Add source level properties (with no prefix)
   * @param context
   */
  @Override
  protected void doConfigure(Context context) throws FlumeException {
    this.context = context;
    headers = new HashMap<String, String>(4);
    tpAndOffsetMetadata = new HashMap<TopicPartition, OffsetAndMetadata>();
    rebalanceFlag = new AtomicBoolean(false);
    kafkaProps = new Properties();

    // can be removed in the next release
    // See https://issues.apache.org/jira/browse/FLUME-2896
    translateOldProperties(context);

    //获取Kafka topic
    String topicProperty = context.getString(KafkaSourceConstants.TOPICS_REGEX);
    if (topicProperty != null && !topicProperty.isEmpty()) {
      // create subscriber that uses pattern-based subscription
      subscriber = new PatternSubscriber(topicProperty);
    } else if ((topicProperty = context.getString(KafkaSourceConstants.TOPICS)) != null &&
               !topicProperty.isEmpty()) {
      // create subscriber that uses topic list subscription
      subscriber = new TopicListSubscriber(topicProperty);
    } else if (subscriber == null) {
      throw new ConfigurationException("At least one Kafka topic must be specified.");
    }

    batchUpperLimit = context.getInteger(KafkaSourceConstants.BATCH_SIZE,
                                         KafkaSourceConstants.DEFAULT_BATCH_SIZE);
    maxBatchDurationMillis = context.getInteger(KafkaSourceConstants.BATCH_DURATION_MS,
                                                KafkaSourceConstants.DEFAULT_BATCH_DURATION);

    useAvroEventFormat = context.getBoolean(KafkaSourceConstants.AVRO_EVENT,
                                            KafkaSourceConstants.DEFAULT_AVRO_EVENT);

    if (log.isDebugEnabled()) {
      log.debug(KafkaSourceConstants.AVRO_EVENT + " set to: {}", useAvroEventFormat);
    }

    zookeeperConnect = context.getString(ZOOKEEPER_CONNECT_FLUME_KEY);
    migrateZookeeperOffsets = context.getBoolean(MIGRATE_ZOOKEEPER_OFFSETS,
        DEFAULT_MIGRATE_ZOOKEEPER_OFFSETS);

    bootstrapServers = context.getString(KafkaSourceConstants.BOOTSTRAP_SERVERS);
    if (bootstrapServers == null || bootstrapServers.isEmpty()) {
      if (zookeeperConnect == null || zookeeperConnect.isEmpty()) {
        throw new ConfigurationException("Bootstrap Servers must be specified");
      } else {

        //如果配置zk,则从zk元数据仓库中获取kafka server的信息

        // For backwards compatibility look up the bootstrap from zookeeper
        log.warn("{} is deprecated. Please use the parameter {}",
            KafkaSourceConstants.ZOOKEEPER_CONNECT_FLUME_KEY,
            KafkaSourceConstants.BOOTSTRAP_SERVERS);

        // Lookup configured security protocol, just in case its not default
        String securityProtocolStr =
            context.getSubProperties(KafkaSourceConstants.KAFKA_CONSUMER_PREFIX)
                .get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        if (securityProtocolStr == null || securityProtocolStr.isEmpty()) {
          securityProtocolStr = CommonClientConfigs.DEFAULT_SECURITY_PROTOCOL;
        }
        bootstrapServers =
            lookupBootstrap(zookeeperConnect, SecurityProtocol.valueOf(securityProtocolStr));
      }
    }

    //获取groupId
    String groupIdProperty =
        context.getString(KAFKA_CONSUMER_PREFIX + ConsumerConfig.GROUP_ID_CONFIG);
    if (groupIdProperty != null && !groupIdProperty.isEmpty()) {
      groupId = groupIdProperty; // Use the new group id property
    }

    if (groupId == null || groupId.isEmpty()) {
      groupId = DEFAULT_GROUP_ID;
      log.info("Group ID was not specified. Using {} as the group id.", groupId);
    }

    //设置kafka consumer自己的参数
    setConsumerProps(context);

    if (log.isDebugEnabled() && LogPrivacyUtil.allowLogPrintConfig()) {
      log.debug("Kafka consumer properties: {}", kafkaProps);
    }

    if (counter == null) {
      counter = new KafkaSourceCounter(getName());
    }
  }

  // We can remove this once the properties are officially deprecated
  private void translateOldProperties(Context ctx) {
    // topic
    String topic = context.getString(KafkaSourceConstants.TOPIC);
    if (topic != null && !topic.isEmpty()) {
      subscriber = new TopicListSubscriber(topic);
      log.warn("{} is deprecated. Please use the parameter {}",
              KafkaSourceConstants.TOPIC, KafkaSourceConstants.TOPICS);
    }

    // old groupId
    groupId = ctx.getString(KafkaSourceConstants.OLD_GROUP_ID);
    if (groupId != null && !groupId.isEmpty()) {
      log.warn("{} is deprecated. Please use the parameter {}",
              KafkaSourceConstants.OLD_GROUP_ID,
              KafkaSourceConstants.KAFKA_CONSUMER_PREFIX + ConsumerConfig.GROUP_ID_CONFIG);
    }
  }

  private void setConsumerProps(Context ctx) {
    kafkaProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                   KafkaSourceConstants.DEFAULT_KEY_DESERIALIZER);
    kafkaProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                   KafkaSourceConstants.DEFAULT_VALUE_DESERIALIZER);
    //Defaults overridden based on config
    kafkaProps.putAll(ctx.getSubProperties(KafkaSourceConstants.KAFKA_CONSUMER_PREFIX));
    //These always take precedence over config
    kafkaProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    if (groupId != null) {
      kafkaProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    }
    kafkaProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                   KafkaSourceConstants.DEFAULT_AUTO_COMMIT);
  }

  /**
   * Generates the Kafka bootstrap connection string from the metadata stored in Zookeeper.
   * Allows for backwards compatibility of the zookeeperConnect configuration.
   */
  private String lookupBootstrap(String zookeeperConnect, SecurityProtocol securityProtocol) {
    ZkUtils zkUtils = ZkUtils.apply(zookeeperConnect, ZK_SESSION_TIMEOUT, ZK_CONNECTION_TIMEOUT,
        JaasUtils.isZkSecurityEnabled());
    try {
      List<BrokerEndPoint> endPoints =
          asJavaListConverter(zkUtils.getAllBrokerEndPointsForChannel(securityProtocol)).asJava();
      List<String> connections = new ArrayList<>();
      for (BrokerEndPoint endPoint : endPoints) {
        connections.add(endPoint.connectionString());
      }
      return StringUtils.join(connections, ',');
    } finally {
      zkUtils.close();
    }
  }

  @VisibleForTesting
  String getBootstrapServers() {
    return bootstrapServers;
  }

  Properties getConsumerProps() {
    return kafkaProps;
  }

  /**
   * Helper function to convert a map of CharSequence to a map of String.
   */
  private static Map<String, String> toStringMap(Map<CharSequence, CharSequence> charSeqMap) {
    Map<String, String> stringMap = new HashMap<String, String>();
    for (Map.Entry<CharSequence, CharSequence> entry : charSeqMap.entrySet()) {
      stringMap.put(entry.getKey().toString(), entry.getValue().toString());
    }
    return stringMap;
  }

  <T> Subscriber<T> getSubscriber() {
    return subscriber;
  }

  @Override
  protected void doStart() throws FlumeException {
    log.info("Starting kafka source{}...", this);

    // As a migration step check if there are any offsets from the group stored in kafka
    // If not read them from Zookeeper and commit them to Kafka
    if (migrateZookeeperOffsets && zookeeperConnect != null && !zookeeperConnect.isEmpty()) {
      // For simplicity we only support migration of a single topic via the TopicListSubscriber.
      // There was no way to define a list of topics or a pattern in the previous Flume version.
      if (subscriber instanceof TopicListSubscriber &&
          ((TopicListSubscriber) subscriber).get().size() == 1) {
        String topicStr = ((TopicListSubscriber) subscriber).get().get(0);
        migrateOffsets(topicStr);
      } else {
        log.info("Will not attempt to migrate offsets " +
            "because multiple topics or a pattern are defined");
      }
    }

    //initialize a consumer.
    consumer = new KafkaConsumer<String, byte[]>(kafkaProps);

    // Subscribe for topics by already specified strategy
    subscriber.subscribe(consumer, new SourceRebalanceListener(rebalanceFlag));

    // Connect to kafka. 1 second is optimal time.
    it = consumer.poll(1000).iterator();
    log.info("Kafka source {} started.", getName());
    counter.start();
  }

  @Override
  protected void doStop() throws FlumeException {
    if (consumer != null) {
      consumer.wakeup();
      consumer.close();
    }
    counter.stop();
    log.info("Kafka Source {} stopped. Metrics: {}", getName(), counter);
  }

  private void migrateOffsets(String topicStr) {
    ZkUtils zkUtils = ZkUtils.apply(zookeeperConnect, ZK_SESSION_TIMEOUT, ZK_CONNECTION_TIMEOUT,
        JaasUtils.isZkSecurityEnabled());
    KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(kafkaProps);
    try {
      Map<TopicPartition, OffsetAndMetadata> kafkaOffsets =
          getKafkaOffsets(consumer, topicStr);
      if (!kafkaOffsets.isEmpty()) {
        log.info("Found Kafka offsets for topic " + topicStr +
            ". Will not migrate from zookeeper");
        log.debug("Offsets found: {}", kafkaOffsets);
        return;
      }

      log.info("No Kafka offsets found. Migrating zookeeper offsets");
      Map<TopicPartition, OffsetAndMetadata> zookeeperOffsets =
          getZookeeperOffsets(zkUtils, topicStr);
      if (zookeeperOffsets.isEmpty()) {
        log.warn("No offsets to migrate found in Zookeeper");
        return;
      }

      log.info("Committing Zookeeper offsets to Kafka");
      log.debug("Offsets to commit: {}", zookeeperOffsets);
      consumer.commitSync(zookeeperOffsets);
      // Read the offsets to verify they were committed
      Map<TopicPartition, OffsetAndMetadata> newKafkaOffsets =
          getKafkaOffsets(consumer, topicStr);
      log.debug("Offsets committed: {}", newKafkaOffsets);
      if (!newKafkaOffsets.keySet().containsAll(zookeeperOffsets.keySet())) {
        throw new FlumeException("Offsets could not be committed");
      }
    } finally {
      zkUtils.close();
      consumer.close();
    }
  }

  private Map<TopicPartition, OffsetAndMetadata> getKafkaOffsets(
      KafkaConsumer<String, byte[]> client, String topicStr) {
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    List<PartitionInfo> partitions = client.partitionsFor(topicStr);
    for (PartitionInfo partition : partitions) {
      TopicPartition key = new TopicPartition(topicStr, partition.partition());
      OffsetAndMetadata offsetAndMetadata = client.committed(key);
      if (offsetAndMetadata != null) {
        offsets.put(key, offsetAndMetadata);
      }
    }
    return offsets;
  }

  private Map<TopicPartition, OffsetAndMetadata> getZookeeperOffsets(ZkUtils client,
                                                                     String topicStr) {
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    ZKGroupTopicDirs topicDirs = new ZKGroupTopicDirs(groupId, topicStr);
    List<String> partitions = asJavaListConverter(
        client.getChildrenParentMayNotExist(topicDirs.consumerOffsetDir())).asJava();
    for (String partition : partitions) {
      TopicPartition key = new TopicPartition(topicStr, Integer.valueOf(partition));
      Option<String> data = client.readDataMaybeNull(
          topicDirs.consumerOffsetDir() + "/" + partition)._1();
      if (data.isDefined()) {
        Long offset = Long.valueOf(data.get());
        offsets.put(key, new OffsetAndMetadata(offset));
      }
    }
    return offsets;
  }
}

//监听 是否进行了重新分配
/**
 * Kafka 数据消费是以 Partition 为单位的，即一个 Partition 只能被一个 Flume 实例消费。
 * 当启动第二个 Flume 实例时，Kafka 会把已经分配给第一个 Flume 实例的 Partition 回收(revoke)后，然后重新分配
 * */
class SourceRebalanceListener implements ConsumerRebalanceListener {
  private static final Logger log = LoggerFactory.getLogger(SourceRebalanceListener.class);
  private AtomicBoolean rebalanceFlag;

  public SourceRebalanceListener(AtomicBoolean rebalanceFlag) {
    this.rebalanceFlag = rebalanceFlag;
  }

  // Set a flag that a rebalance has occurred. Then commit already read events to kafka.
  //发生再均衡 通知consumer 任务被取消了
  public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    for (TopicPartition partition : partitions) {
      log.info("topic {} - partition {} revoked.", partition.topic(), partition.partition());
      rebalanceFlag.set(true);
    }
  }

  //重新分配partition之后和消费者开始读取消息之前被调用。 通知consumer新的任务来了
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    for (TopicPartition partition : partitions) {
      log.info("topic {} - partition {} assigned.", partition.topic(), partition.partition());
    }
  }
}
