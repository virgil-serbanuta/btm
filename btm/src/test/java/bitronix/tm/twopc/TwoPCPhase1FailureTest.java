/*
 * Copyright (C) 2006-2013 Bitronix Software (http://www.bitronix.be)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bitronix.tm.twopc;

import bitronix.tm.BitronixTransactionManager;
import bitronix.tm.TransactionManagerServices;
import bitronix.tm.journal.Journal;
import bitronix.tm.mock.AbstractMockJdbcTest;
import bitronix.tm.mock.events.Event;
import bitronix.tm.mock.events.EventRecorder;
import bitronix.tm.mock.events.JournalLogEvent;
import bitronix.tm.mock.events.LocalRollbackEvent;
import bitronix.tm.mock.events.XAResourcePrepareEvent;
import bitronix.tm.mock.events.XAResourceRollbackEvent;
import bitronix.tm.mock.resource.MockJournal;
import bitronix.tm.mock.resource.MockXAResource;
import bitronix.tm.mock.resource.jdbc.MockDriver;
import bitronix.tm.mock.resource.jdbc.MockitoXADataSource;
import bitronix.tm.resource.jdbc.PooledConnectionProxy;
import bitronix.tm.resource.jdbc.PoolingDataSource;
import bitronix.tm.resource.jdbc.lrc.LrcXADataSource;
import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.xa.XAException;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author Ludovic Orban
 */
public class TwoPCPhase1FailureTest extends TestCase {
    private final static Logger log = LoggerFactory.getLogger(Phase1FailureTest.class);


    private PoolingDataSource poolingDataSource1;
    private PoolingDataSource poolingDataSource2;
    private PoolingDataSource poolingDataSource3;
    private PoolingDataSource poolingDataSourceLrc;
    private BitronixTransactionManager tm;

    /**
     * Test scenario:
     *
     * XAResources: 3
     * TX timeout: 10s
     * TX resolution: rollback
     *
     * @throws Exception if any error happens.
     */
    public void testPrepareFailure() throws Exception {
        tm.begin();
        tm.setTransactionTimeout(10); // TX must not timeout

        Connection connection1 = poolingDataSource1.getConnection();
        connection1.createStatement();

        Connection connection2 = poolingDataSource2.getConnection();
        PooledConnectionProxy handle = (PooledConnectionProxy) connection2;
        XAConnection xaConnection2 = (XAConnection) AbstractMockJdbcTest.getWrappedXAConnectionOf(handle.getPooledConnection());
        connection2.createStatement();

        Connection connection3 = poolingDataSource2.getConnection();
        connection3.createStatement();

        MockXAResource mockXAResource2 = (MockXAResource) xaConnection2.getXAResource();
        mockXAResource2.setPrepareException(createXAException("resource 2 prepare failed", XAException.XAER_RMERR));

        try {
            tm.commit();
            fail("TM should have thrown an exception");
        } catch (RollbackException ex) {
            assertTrue(ex.getMessage().matches("transaction failed to prepare: a Bitronix Transaction with GTRID (.*?) status=ROLLEDBACK, 3 resource\\(s\\) enlisted (.*?)"));

            assertTrue(ex.getCause().getMessage().matches("transaction failed during prepare of a Bitronix Transaction with GTRID (.*?), status=PREPARING, 3 resource\\(s\\) enlisted (.*?): resource\\(s\\) \\[pds2\\] threw unexpected exception"));

            assertEquals("collected 1 exception(s):" + System.getProperty("line.separator") +
                    " [pds2 - javax.transaction.xa.XAException(XAER_RMERR) - resource 2 prepare failed]", ex.getCause().getCause().getMessage());
        }

        log.info(EventRecorder.dumpToString());

        // we should find a ROLLEDBACK status in the journal log
        // and 3 prepare tries (1 successful for resources 1 and 3, 1 failed for resource 2)
        // and 3 rollback tries (1 successful for each resource)
        int journalRollbackEventCount = 0;
        int prepareEventCount = 0;
        int rollbackEventCount = 0;
        List events = EventRecorder.getOrderedEvents();
        for (int i = 0; i < events.size(); i++) {
            Event event = (Event) events.get(i);

            if (event instanceof XAResourceRollbackEvent)
                rollbackEventCount++;

            if (event instanceof XAResourcePrepareEvent)
                prepareEventCount++;

            if (event instanceof JournalLogEvent) {
                if (((JournalLogEvent) event).getStatus() == Status.STATUS_ROLLEDBACK)
                    journalRollbackEventCount++;
            }
        }
        assertEquals("TM should have journaled 1 ROLLEDBACK status", 1, journalRollbackEventCount);
        assertEquals("TM haven't properly tried to prepare", 3, prepareEventCount);
        assertEquals("TM haven't properly tried to rollback", 3, rollbackEventCount);
    }


    @Override
    protected void setUp() throws Exception {
        EventRecorder.clear();

        // change disk journal into mock journal
        Field field = TransactionManagerServices.class.getDeclaredField("journalRef");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<Journal> journalRef = (AtomicReference<Journal>) field.get(TransactionManagerServices.class);
        journalRef.set(new MockJournal());

        poolingDataSource1 = new PoolingDataSource();
        poolingDataSource1.setClassName(MockitoXADataSource.class.getName());
        poolingDataSource1.setUniqueName("pds1");
        poolingDataSource1.setMinPoolSize(5);
        poolingDataSource1.setMaxPoolSize(5);
        poolingDataSource1.setAutomaticEnlistingEnabled(true);
        poolingDataSource1.init();

        poolingDataSource2 = new PoolingDataSource();
        poolingDataSource2.setClassName(MockitoXADataSource.class.getName());
        poolingDataSource2.setUniqueName("pds2");
        poolingDataSource2.setMinPoolSize(5);
        poolingDataSource2.setMaxPoolSize(5);
        poolingDataSource2.setAutomaticEnlistingEnabled(true);
        poolingDataSource2.init();

        poolingDataSource3 = new PoolingDataSource();
        poolingDataSource3.setClassName(MockitoXADataSource.class.getName());
        poolingDataSource3.setUniqueName("pds3");
        poolingDataSource3.setMinPoolSize(5);
        poolingDataSource3.setMaxPoolSize(5);
        poolingDataSource3.setAutomaticEnlistingEnabled(true);
        poolingDataSource3.init();

        poolingDataSourceLrc = new PoolingDataSource();
        poolingDataSourceLrc.setClassName(LrcXADataSource.class.getName());
        poolingDataSourceLrc.setUniqueName("pds4_lrc");
        poolingDataSourceLrc.setMinPoolSize(5);
        poolingDataSourceLrc.setMaxPoolSize(5);
        poolingDataSourceLrc.setAllowLocalTransactions(true);
        poolingDataSourceLrc.getDriverProperties().setProperty("driverClassName", MockDriver.class.getName());
        poolingDataSourceLrc.getDriverProperties().setProperty("user", "user");
        poolingDataSourceLrc.getDriverProperties().setProperty("password", "password");
        poolingDataSourceLrc.init();

        tm = TransactionManagerServices.getTransactionManager();
    }

    @Override
    protected void tearDown() throws Exception {
        poolingDataSource1.close();
        poolingDataSource2.close();
        poolingDataSource3.close();
        poolingDataSourceLrc.close();
        tm.shutdown();
    }

    private XAException createXAException(String msg, int errorCode) {
        XAException prepareException = new XAException(msg);
        prepareException.errorCode = errorCode;
        return prepareException;
    }

}
