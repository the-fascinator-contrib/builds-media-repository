/*
 * The Fascinator - Media Repository - DiReCt New Items 'Api'
 * Copyright (C) 2010 University of Southern Queensland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package au.edu.usq.fascinator.media.direct;

import java.io.IOException;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import au.edu.usq.fascinator.common.GenericListener;
import au.edu.usq.fascinator.common.JsonConfig;
import au.edu.usq.fascinator.common.JsonConfigHelper;

/**
 * A queue that 'publishes' new items to DiReCt. A polling script on the DiReCt
 * server will check for new content periodically.
 * 
 * @author Greg Pendlebury
 */
public class DirectOutgoingApi implements GenericListener {

    /** The ID of this queue in the service loader */
    public static final String LISTENER_ID = "DirectOutgoingApi";

    /** Message queue of outgoing respository messages heading to DiReCt */
    public static final String EXTERNAL_QUEUE = "newDirectItems";

    /**
     * Message queue of outgoing respository messages coming from internal
     * objects
     */
    public static final String INTERNAL_QUEUE = DirectMessageService.DIRECT_QUEUE;

    /** Logging */
    private Logger log = LoggerFactory.getLogger(DirectOutgoingApi.class);

    /** JSON configuration */
    private JsonConfig globalConfig;

    /** JMS connection */
    private Connection connection;

    /** JMS Session */
    private Session session;

    /** Message Consumer instance */
    private MessageConsumer consumer;

    /** Message Producer instance */
    private MessageProducer producer;

    /** Thread reference */
    private Thread thread;

    /**
     * Constructor required by ServiceLoader. Be sure to use init()
     * 
     */
    public DirectOutgoingApi() {
        thread = new Thread(this, LISTENER_ID);
    }

    /**
     * Start thread running
     * 
     */
    @Override
    public void run() {
        try {
            log.info("Starting DiReCt Outgoing API: '{}'", INTERNAL_QUEUE);

            // Get a connection to the broker
            String brokerUrl = globalConfig.get("messaging/url",
                    ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL);
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Incoming messages
            consumer = session.createConsumer(session
                    .createQueue(INTERNAL_QUEUE));
            consumer.setMessageListener(this);

            // Outgoing messages
            producer = session.createProducer(session
                    .createQueue(EXTERNAL_QUEUE));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            connection.start();
        } catch (JMSException ex) {
            log.error("Error starting message thread!", ex);
        }
    }

    /**
     * Initialization method
     * 
     * @param config
     *            Configuration to use
     * @throws IOException
     *             if the configuration file not found
     */
    @Override
    public void init(JsonConfigHelper config) throws Exception {
        try {
            thread.setName(LISTENER_ID);
            globalConfig = new JsonConfig();
        } catch (IOException ioe) {
            log.error("Failed to read configuration: {}", ioe.getMessage());
            throw ioe;
        }
    }

    /**
     * Return the ID string for this listener
     * 
     * @returns String ID of this class.
     */
    @Override
    public String getId() {
        return LISTENER_ID;
    }

    /**
     * Start the message listener
     * 
     * @throws Exception
     *             if an error occurred starting the JMS connection
     */
    @Override
    public void start() throws Exception {
        thread.start();
    }

    /**
     * Stop the listener.
     * 
     * @throws Exception
     *             if an error occurred stopping the JMS connection
     * 
     */
    @Override
    public void stop() throws Exception {
        log.info("Stopping DiReCt Outgoing API...");
        if (producer != null) {
            try {
                producer.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close producer: {}", jmse.getMessage());
            }
        }
        if (consumer != null) {
            try {
                consumer.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close consumer: {}", jmse.getMessage());
                throw jmse;
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close consumer session: {}", jmse);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException jmse) {
                log.warn("Failed to close connection: {}", jmse);
            }
        }
    }

    /**
     * Callback function for incoming messages.
     * 
     * @param message
     *            The incoming message
     */
    @Override
    public void onMessage(Message message) {
        MDC.put("name", LISTENER_ID);
        try {
            String text = ((TextMessage) message).getText();
            // Verify the json is valid
            JsonConfigHelper msgJson = new JsonConfigHelper(text);
            // Send it to DiReCt
            producer.send(session.createTextMessage(msgJson.toString()));
        } catch (JMSException jmse) {
            log.error("Failed to receive message: {}", jmse.getMessage());
        } catch (IOException ioe) {
            log.error("Failed to parse message: {}", ioe.getMessage());
        } catch (Exception ex) {
            log.error("Unknown error during message processing!", ex);
        }
    }

    /**
     * Sets the priority level for the thread. Used by the OS.
     * 
     * @param newPriority
     *            The priority level to set the thread at
     */
    @Override
    public void setPriority(int newPriority) {
        if (newPriority >= Thread.MIN_PRIORITY
                && newPriority <= Thread.MAX_PRIORITY) {
            thread.setPriority(newPriority);
        }
    }
}
