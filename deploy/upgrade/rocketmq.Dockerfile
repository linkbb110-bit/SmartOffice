FROM apache/rocketmq:5.3.1
# Pre-create mount targets so new named volumes inherit the application's owner.
USER root
RUN mkdir -p /home/rocketmq/logs /home/rocketmq/store \
    && chown rocketmq:rocketmq /home/rocketmq/logs /home/rocketmq/store
USER rocketmq
