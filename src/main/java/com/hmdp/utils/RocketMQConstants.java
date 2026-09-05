package com.hmdp.utils;

/**
 * RocketMQ 消息队列常量
 */
public class RocketMQConstants {

    public static final String NAME_SERVER = "127.0.0.1:9876";

    /** 领券订单 Topic */
    public static final String ORDER_TOPIC = "order-seckill-topic";
    /** 创建订单消息 Tag */
    public static final String ORDER_TAG_CREATE = "CREATE";

    public static final String ORDER_PRODUCER_GROUP = "seckill-producer-group";
    /** 事务消息生产者组（与普通生产者组区分，Broker 按组回查） */
    public static final String ORDER_TX_PRODUCER_GROUP = "seckill-tx-producer-group";
    public static final String ORDER_CONSUMER_GROUP = "order-consumer-group";
}
