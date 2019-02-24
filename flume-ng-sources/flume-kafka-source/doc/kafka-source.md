
### 基础知识
````

 Kafka 数据消费是以 Partition 为单位的，即一个 Partition 只能被一个 Flume 实例消费。
 当启动第二个 Flume 实例时，Kafka 会把已经分配给第一个 Flume 实例的 Partition 回收(revoke)后，
 然后重新分配
 
 另一方面，Flume 为了满足事务语义，需要等每条 Kafka Record 真正放到 Channel 后，
 才能向 Kafka 确认 (调用 consumer.commitSync)。 在 Kafka 回收，
 分配过程 (rebalance) 中，已经接收到 Flume 端但还未被 Flume 放到 Channel 的 Records (自然也就没有向 Kafka 确认) 
 就被Kafka 认为未被消费；特别是属于将来被分配给其他 Flume 实例的 Partition 上的 Records，就会再次被新的 Flume 实例消费，
 造成重复加载。

````

### 优化方案
````
1-首先当新增一个消费者agent的时候,会触发onPartitionsRevoked。
如果此时正在运行的消费者agent,已经读取了部分数据到eventList,但是还没有达到批量提交的数量,
按照原本代码的逻辑是进行break,然后提交到channel,最后进行提交到kafka.

2-如果新增的消费者agent在 正在运行的消费者agent 最后进行提交到kafka 之前已经开始进行消费
则会进行重复消费

3-我们可以修改代码逻辑, 不管新增的消费者agent,让它启动后去执行消费的逻辑,
但是对于正在运行的消费者agent, 如果已经读取了部分数据到eventList但是还没有达到批量提交的数量,
则可以直接清空,不让他进行后续的提交操作。同时设置正在运行的消费者agent consumer回到上一次提交的
offset.

````


### 测试











