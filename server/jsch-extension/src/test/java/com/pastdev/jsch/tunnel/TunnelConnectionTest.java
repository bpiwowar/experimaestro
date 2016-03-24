package com.pastdev.jsch.tunnel;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.jcraft.jsch.JSchException;
import com.pastdev.jsch.DefaultSessionFactory;
import com.pastdev.jsch.IOUtils;
import com.pastdev.jsch.SessionFactory;


public class TunnelConnectionTest {
    private static Logger logger = LoggerFactory.getLogger( TunnelConnectionTest.class );
    private static final Charset UTF8 = Charset.forName( "UTF-8" );
    private static SessionFactory sessionFactory;
    private static Properties properties;

    private String expected = "This will be amazing if it works";
    private StringBuffer serviceBuffer;
    private int servicePort = 59703;
    private Thread serviceThread;

    private final ReentrantLock serviceLock = new ReentrantLock();
    private final Condition serviceBufferReady = serviceLock.newCondition();
    private final Condition serviceConnectAccepted = serviceLock.newCondition();
    private final Condition serviceConnected = serviceLock.newCondition();
    private final Condition serviceReady = serviceLock.newCondition();
    private final Condition serviceWrittenTo = serviceLock.newCondition();

    @BeforeClass
    public static void initializeClass() {
        InputStream inputStream = null;
        try {
            inputStream = ClassLoader.getSystemResourceAsStream( "configuration.properties" );
            Assume.assumeNotNull( inputStream );
            properties = new Properties();
            properties.load( inputStream );
        }
        catch ( IOException e ) {
            logger.warn( "cant find properties file (tests will be skipped): {}", e.getMessage() );
            logger.debug( "cant find properties file:", e );
            properties = null;
            return;
        }
        finally {
            if ( inputStream != null ) {
                try {
                    inputStream.close();
                }
                catch ( IOException e ) {
                    // really, i dont care...
                }
            }
        }

        String knownHosts = properties.getProperty( "ssh.knownHosts" );
        String privateKey = properties.getProperty( "ssh.privateKey" );
        String username = properties.getProperty( "scp.out.test.username" );
        String hostname = "localhost";
        int port = Integer.parseInt( properties.getProperty( "scp.out.test.port" ) );

        DefaultSessionFactory defaultSessionFactory = new DefaultSessionFactory( username, hostname, port );
        try {
            defaultSessionFactory.setKnownHosts( knownHosts );
            defaultSessionFactory.setIdentityFromPrivateKey( privateKey );
        }
        catch ( JSchException e ) {
            logger.error( "Failed to configure default session, skipping tests: {}", e.getMessage() );
            logger.debug( "Failed to configure default session, skipping tests:", e );
            Assume.assumeNoException( e );
        }
        sessionFactory = defaultSessionFactory;
    }

    @After
    public void afterTest() throws InterruptedException {
        serviceThread.join();
    }

    @Before
    public void beforeTest() throws InterruptedException {
        Assume.assumeNotNull( properties ); // skip tests if properties not set
        serviceBuffer = new StringBuffer();
        serviceThread = new Thread( new Runnable() {
            public void run() {
                ServerSocket serverSocket = null;
                InputStream inputStream = null;

                serviceLock.lock();
                try {
                    serverSocket = new ServerSocket( servicePort );
                    logger.debug( "opening service on port {}", servicePort );
                    serviceReady.signalAll();
                    serviceConnected.await();
                    logger.trace( "waiting for connection" );
                    inputStream = serverSocket.accept().getInputStream();
                    serviceConnectAccepted.signalAll();
                    logger.trace( "connected, now wait for write" );
                    serviceWrittenTo.await();
                    logger.trace( "accepted connection, now read data" );
                    String data = IOUtils.copyToString( inputStream, UTF8 );
                    logger.trace( "read {}", data );
                    serviceBuffer.append( data );
                    serviceBufferReady.signalAll();
                }
                catch ( Exception e ) {
                    logger.error( "failed for to open service on port {}: ", servicePort, e );
                    logger.debug( "failed:", e );
                }
                finally {
                    serviceLock.unlock();

                    logger.debug( "closing down service on port {}", servicePort );
                    IOUtils.closeAndLogException( inputStream );
                    IOUtils.closeAndLogException( serverSocket );
                }
            }
        } );
        logger.debug( "starting service" );
        serviceThread.start();

        serviceLock.lock();
        try {
            logger.trace( "wait for serviceReady" );
            serviceReady.await(); // wait for service to open socket
            logger.trace( "service is ready now!" );
        }
        finally {
            serviceLock.unlock();
        }
    }

    @Test
    public void testService() {
        assertEquals( expected, writeToService( servicePort, expected ) );
    }

    @Test
    public void testConnection() {
        final int tunnelPort1 = 59701;
        TunnelConnection tunnelConnection = null;
        try {
            tunnelConnection = new TunnelConnection( sessionFactory,
                    new Tunnel( tunnelPort1, "localhost", servicePort ) );
            tunnelConnection.open();

            assertEquals( expected, writeToService( tunnelPort1, expected ) );
        }
        catch ( Exception e ) {
            logger.error( "failed for {}: {}", tunnelConnection, e );
            logger.debug( "failed:", e );
            fail( e.getMessage() );
        }
        finally {
            logger.debug( "close" );
            IOUtils.closeAndLogException( tunnelConnection );
        }
    }

    @Test
    public void testDynamicPortConnection() {
        TunnelConnection tunnelConnection = null;
        try {
            String hostname = "localhost";
            tunnelConnection = new TunnelConnection( sessionFactory,
                    new Tunnel( hostname, servicePort ) );
            tunnelConnection.open();

            assertEquals( expected, writeToService(
                    tunnelConnection.getTunnel( hostname, servicePort )
                            .getAssignedLocalPort(), expected ) );
        }
        catch ( Exception e ) {
            logger.error( "failed for {}: {}", tunnelConnection, e );
            logger.debug( "failed:", e );
            fail( e.getMessage() );
        }
        finally {
            logger.debug( "close" );
            IOUtils.closeAndLogException( tunnelConnection );
        }
    }

    private String writeToService( int port, String data ) {
        Socket socket = null;
        serviceLock.lock();
        try {
            logger.debug( "connecting to service through port: {}", port );
            socket = new Socket( "localhost", port );
            logger.trace( "connected" );
            serviceConnected.signalAll();
            serviceConnectAccepted.await();
            logger.trace( "now write to service" );

            OutputStream outputStream = null;
            try {
                outputStream = socket.getOutputStream();
                IOUtils.copyFromString( expected, UTF8, outputStream );
            }
            finally {
                IOUtils.closeAndLogException( outputStream );
            }

            logger.trace( "service written to" );
            serviceWrittenTo.signalAll();
            logger.trace( "wait for serviceBuffer" );
            serviceBufferReady.await(); // wait for buffer to be updated
            return serviceBuffer.toString();
        }
        catch ( Exception e ) {
            logger.error( "failed for service on port {}: {}", servicePort, e );
            logger.debug( "failed:", e );
            fail( e.getMessage() );
            return null; // to make compiler happy
        }
        finally {
            serviceLock.unlock();

            logger.debug( "close" );
            IOUtils.closeAndLogException( socket );
        }
    }
}
