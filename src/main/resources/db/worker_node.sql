-- 百度 UidGenerator worker 节点表
-- 应用启动时 DisposableWorkerIdAssigner 插入一行，自增主键即 workerId（可容纳约 400 万节点）
-- 参考官方 db 脚本：https://github.com/baidu/uid-generator

DROP TABLE IF EXISTS worker_node;

CREATE TABLE worker_node (
  id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增 id（即 workerId）',
  host_name   VARCHAR(64) NOT NULL COMMENT 'host name（容器为 HOST，实体机为 IP）',
  port        VARCHAR(64) NOT NULL COMMENT 'port（实体机为 timestamp-random）',
  type        INT         NOT NULL COMMENT '节点类型: 1-CONTAINER, 2-ACTUAL',
  launch_date DATE        NOT NULL COMMENT '启动日期',
  modified    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  created     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8 COMMENT = 'UID Generator worker node 表';
