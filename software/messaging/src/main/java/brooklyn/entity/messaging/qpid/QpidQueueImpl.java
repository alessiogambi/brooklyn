package brooklyn.entity.messaging.qpid;

import static java.lang.String.format;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import brooklyn.entity.messaging.amqp.AmqpExchange;
import brooklyn.event.feed.jmx.JmxAttributePollConfig;
import brooklyn.event.feed.jmx.JmxFeed;
import brooklyn.util.exceptions.Exceptions;

public class QpidQueueImpl extends QpidDestinationImpl implements QpidQueue {
    public QpidQueueImpl() {
    }

    @Override
    public void onManagementStarting() {
        super.onManagementStarting();
        setAttribute(QUEUE_NAME, getName());
        try {
            exchange = new ObjectName(format("org.apache.qpid:type=VirtualHost.Exchange,VirtualHost=\"%s\",name=\"%s\",ExchangeType=direct", virtualHost, getExchangeName()));
        } catch (MalformedObjectNameException e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    protected void connectSensors() {
        String queue = format("org.apache.qpid:type=VirtualHost.Queue,VirtualHost=\"%s\",name=\"%s\"", virtualHost, getName());
        
        jmxFeed = JmxFeed.builder()
                .entity(this)
                .helper(jmxHelper)
                .pollAttribute(new JmxAttributePollConfig<Integer>(QUEUE_DEPTH_BYTES)
                        .objectName(queue)
                        .attributeName("QueueDepth"))
                .pollAttribute(new JmxAttributePollConfig<Integer>(QUEUE_DEPTH_MESSAGES)
                        .objectName(queue)
                        .attributeName("MessageCount"))
                .build();
    }

    @Override
    public String getExchangeName() {
        return AmqpExchange.DIRECT;
    }
}
