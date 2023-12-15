package com.xiaojukeji.know.streaming.km.task.service.listener;

import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;
import com.xiaojukeji.know.streaming.km.common.bean.entity.cluster.ClusterPhy;
import com.xiaojukeji.know.streaming.km.common.bean.event.cluster.ClusterPhyAddedEvent;
import com.xiaojukeji.know.streaming.km.common.component.SpringTool;
import com.xiaojukeji.know.streaming.km.common.utils.BackoffUtils;
import com.xiaojukeji.know.streaming.km.common.utils.FutureUtil;
import com.xiaojukeji.know.streaming.km.persistence.cache.LoadedClusterPhyCache;
import com.xiaojukeji.know.streaming.km.task.kafka.metadata.AbstractAsyncMetadataDispatchTask;
import com.xiaojukeji.know.streaming.km.task.kafka.metrics.AbstractAsyncMetricsDispatchTask;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TaskClusterAddedListener implements ApplicationListener<ClusterPhyAddedEvent> {
    private static final ILog LOGGER = LogFactory.getLog(TaskClusterAddedListener.class);

    @Override
    public void onApplicationEvent(ClusterPhyAddedEvent event) {
        LOGGER.info("method=onApplicationEvent||clusterPhyId={}||msg=listened new cluster", event.getClusterPhyId());
        Long now = System.currentTimeMillis();

        // 交由KS自定义的线程池，异步执行任务
        FutureUtil.quickStartupFutureUtil.submitTask(() -> triggerAllTask(event.getClusterPhyId(), now));
    }

    private void triggerAllTask(Long clusterPhyId, Long startTimeUnitMs) {
        ClusterPhy tempClusterPhy = null;

        // 120秒内无加载进来，则直接返回退出
        while (System.currentTimeMillis() - startTimeUnitMs <= 120L * 1000L) {
            /**
             * 等待 ScheduleFlushClusterTask 任务加载完成 同时发布不同的事件 (ADD、DELETE、EDIT) 执行的逻辑为空
             */
            tempClusterPhy = LoadedClusterPhyCache.getByPhyId(clusterPhyId);
            if (tempClusterPhy != null) {
                break;
            }

            BackoffUtils.backoff(1000);
        }

        if (tempClusterPhy == null) {
            return;
        }

        // 获取到之后，再延迟5秒，保证相关的集群都被正常加载进来，这里的5秒不固定
        BackoffUtils.backoff(5000);
        final ClusterPhy clusterPhy = tempClusterPhy;

        // 集群执行集群元信息同步
        /**
         * 从 Spring IOC 容器中获取 AbstractAsyncMetadataDispatchTask 所有子类 也即定时任务 具体子类如下 (按照 IDEA 提示整理) :
         * 1 SyncBrokerConfigDiffTask            : 比较每个 Kafka 集群 Broker 之间不同的配置
         * 2 SyncBrokerTask                      : 维护哪些 kafka 集群 Broker 存活或者宕机
         * 3 SyncConnectClusterAndWorkerTask
         * 4 SyncControllerTask                  : 维护哪些 Kafka 集群的 Controller 信息
         * 5 SyncKafkaAclTask                    : 维护哪些 kafka 集群 ACL 认证信息
         * 6 SyncKafkaGroupTask                  : 维护哪些 kafka 集群消费者组信息
         * 7 SyncKafkaUserTask                   : 维护哪些 Kafka 集群用户信息
         * 8 SyncPartitionTask                   : 维护哪些 Kafka 集群主题分区信息
         * 9 SyncTopicConfigTask                 : 维护哪些 Kafka 集群主题配置信息
         * 10 SyncTopicTask                      : 维护哪些 Kafka 集群主题信息
         * 11 SyncZookeeperTask                  : 维护哪些 Kafka 集群 Zookeeper 信息
         */
        List<AbstractAsyncMetadataDispatchTask> metadataServiceList = new ArrayList<>(SpringTool.getBeansOfType(AbstractAsyncMetadataDispatchTask.class).values());
        for (AbstractAsyncMetadataDispatchTask dispatchTask : metadataServiceList) {
            try {
                dispatchTask.asyncProcessSubTask(clusterPhy, startTimeUnitMs);
            } catch (Exception e) {
                // ignore
            }
        }

        // 再延迟5秒，保证集群元信息都已被正常同步至DB，这里的5秒不固定
        BackoffUtils.backoff(5000);

        // 集群集群指标采集
        /**
         * 从 Spring IOC 容器中获取 AbstractAsyncMetricsDispatchTask 所有子类 也即定时任务 具体子类如下 (按照 IDEA 提示整理) :
         * 1 BrokerHealthCheckTask
         * 2 BrokerMetricCollectorTask
         * 3 ClusterHealthCheckTask
         * 4 ClusterMetricCollectorTask
         * 5 GroupHealthCheckTask
         * 6 GroupMetricCollectorTask
         * 7 PartitionMetricCollectorTask
         * 8 TopicHealthCheckTask
         * 9 TopicMetricCollectorTask
         * 10 ZookeeperHealthCheckTask
         * 11 ZookeeperMetricCollectorTask
         */
        List<AbstractAsyncMetricsDispatchTask> metricsServiceList = new ArrayList<>(SpringTool.getBeansOfType(AbstractAsyncMetricsDispatchTask.class).values());
        for (AbstractAsyncMetricsDispatchTask dispatchTask : metricsServiceList) {
            try {
                dispatchTask.asyncProcessSubTask(clusterPhy, startTimeUnitMs);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
