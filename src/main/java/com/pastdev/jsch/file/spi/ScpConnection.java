package com.pastdev.jsch.file.spi;


import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Stack;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.jcraft.jsch.Channel;
import com.jcraft.jsch.JSchException;
import com.pastdev.jsch.JSchIOException;
import com.pastdev.jsch.command.CommandRunner.ChannelExecWrapper;
import com.pastdev.jsch.file.SshPath;
import com.pastdev.jsch.file.attribute.BasicFileAttributes;


/**
 * Based on protocol information found <a
 * href="https://blogs.oracle.com/janp/entry/how_the_scp_protocol_works"
 * >here</a>
 * 
 * @author LTHEISEN
 * 
 */
public class ScpConnection implements Closeable {
    private static Logger logger = LoggerFactory.getLogger( ScpConnection.class );
    private static final Charset US_ASCII = Charset.forName( "US-ASCII" );

    private Channel channel;
    private Stack<CurrentEntry> entryStack;
    private InputStream inputStream;
    private OutputStream outputStream;
    private SshPath connectionPath;

    ScpConnection( ScpEntry entry, ScpMode scpMode ) throws JSchException, IOException {
        this.connectionPath = entry.path();
        String command = getCommand( connectionPath, scpMode );
        ChannelExecWrapper channel = connectionPath.getFileSystem().provider().getCommandRunner().open( command );

        outputStream = channel.getOutputStream();
        inputStream = channel.getInputStream();

        if ( scpMode == ScpMode.FROM ) {
            writeAck();
        }
        else if ( scpMode == ScpMode.TO ) {
            checkAck();
        }

        this.entryStack = new Stack<CurrentEntry>();
    }

    private static String getCommand( SshPath path, ScpMode scpMode ) throws IOException {
        BasicFileAttributes attributes = path.getFileSystem().provider().readAttributes( path, BasicFileAttributes.class );

        StringBuilder command = null;
        switch ( scpMode ) {
            case TO:
                command = new StringBuilder( "scp -tq" );
                break;
            case FROM:
                command = new StringBuilder( "scp -fq" );
        }

        if ( attributes.isDirectory() ) {
            command.append( "r" );
        }

        return command.append( " " ).append( path ).toString();
    }

    /**
     * Throws an JSchIOException if ack was in error. Ack codes are:
     * 
     * <pre>
     *   0 for success,
     *   1 for error,
     *   2 for fatal error
     * </pre>
     * 
     * Also throws, IOException if unable to read from the InputStream. If
     * nothing was thrown, ack was a success.
     */
    private int checkAck() throws IOException {
        logger.trace( "wait for ack" );
        int b = inputStream.read();
        logger.debug( "ack response: '{}'", b );

        if ( b == 1 || b == 2 ) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ( (c = inputStream.read()) != '\n' ) {
                sb.append( (char) c );
            }
            if ( b == 1 || b == 2 ) {
                throw new JSchIOException( sb.toString() );
            }
        }

        return b;
    }

    public void close() throws IOException {
        IOException toThrow = null;
        try {
            while ( !entryStack.isEmpty() ) {
                entryStack.pop().complete();
            }
        }
        catch ( IOException e ) {
            toThrow = e;
        }

        try {
            if ( outputStream != null ) {
                outputStream.close();
            }
        }
        catch ( IOException e ) {
            logger.error( "failed to close outputStream: {}", e.getMessage() );
            logger.debug( "failed to close outputStream:", e );
        }

        try {
            if ( inputStream != null ) {
                inputStream.close();
            }
        }
        catch ( IOException e ) {
            logger.error( "failed to close inputStream: {}", e.getMessage() );
            logger.debug( "failed to close inputStream:", e );
        }

        if ( channel != null && channel.isConnected() ) {
            channel.disconnect();
        }

        if ( toThrow != null ) {
            throw toThrow;
        }
    }

    public void closeEntry() throws IOException {
        entryStack.pop().complete();
    }

    public InputStream getCurrentInputStream() {
        if ( entryStack.isEmpty() ) {
            return null;
        }
        CurrentEntry currentEntry = entryStack.peek();
        return (currentEntry instanceof InputStream) ? (InputStream) currentEntry : null;
    }

    public OutputStream getCurrentOuputStream() {
        if ( entryStack.isEmpty() ) {
            return null;
        }
        CurrentEntry currentEntry = entryStack.peek();
        return (currentEntry instanceof OutputStream) ? (OutputStream) currentEntry : null;
    }

    public ScpEntry getNextEntry() throws IOException {
        if ( !entryStack.isEmpty() && !entryStack.peek().isDirectoryEntry() ) {
            closeEntry();
        }

        ScpEntry entry = parseMessage();
        if ( entry == null ) return null;
        if ( entry.isEndOfDirectory() ) {
            while ( !entryStack.isEmpty() ) {
                boolean isDirectory = entryStack.peek().isDirectoryEntry();
                closeEntry();
                if ( isDirectory ) {
                    break;
                }
            }
        }
        else if ( entry.isDirectory() ) {
            entryStack.push( new InputDirectoryEntry( entry ) );
        }
        else {
            entryStack.push( new EntryInputStream( entry ) );
        }
        return entry;
    }

    /**
     * Parses SCP protocol messages, for example:
     * 
     * <pre>
     *     File:          C0640 13 test.txt 
     *     Directory:     D0750 0 testdir 
     *     End Directory: E
     * </pre>
     * 
     * @return An ScpEntry for a file (C), directory (D), end of directory (E),
     *         or null when no more messages are available.
     * @throws IOException
     */
    private ScpEntry parseMessage() throws IOException {
        int ack = checkAck();
        if ( ack == -1 ) return null; // end of stream

        char type = (char) ack;

        ScpEntry scpEntry = null;
        if ( type == 'E' ) {
            scpEntry = ScpEntry.newEndOfDirectoryEntry();
            readMessageSegment(); // read and discard the \n
        }
        else if ( type == 'C' || type == 'D' ) {
            String mode = readMessageSegment();
            String sizeString = readMessageSegment();
            if ( sizeString == null ) return null;
            long size = Long.parseLong( sizeString );
            String name = readMessageSegment();
            if ( name == null ) return null;

            SshPath path = entryStack.peek().path().resolve( name );
            scpEntry = type == 'C'
                    ? ScpEntry.newRegularFileEntry( path, mode, size )
                    : ScpEntry.newDirectoryEntry( path, mode );
        }
        else {
            throw new UnsupportedOperationException( "unknown protocol message type " + type );
        }

        logger.debug( "read '{}'", scpEntry );
        return scpEntry;
    }

    public void putEndOfDirectory() throws IOException {
        while ( !entryStack.isEmpty() ) {
            boolean isDirectory = entryStack.peek().isDirectoryEntry();
            closeEntry();
            if ( isDirectory ) {
                break;
            }
        }
    }

    public void putNextEntry( ScpEntry entry ) throws IOException {
        if ( entry.isEndOfDirectory() ) {
            putEndOfDirectory();
            return;
        }
        if ( !entryStack.isEmpty() ) {
            CurrentEntry currentEntry = entryStack.peek();
            if ( !currentEntry.isDirectoryEntry() ) {
                // auto close previous file entry
                closeEntry();
            }
        }

        if ( entry.isDirectory() ) {
            entryStack.push( new OutputDirectoryEntry( entry ) );
        }
        else {
            entryStack.push( new EntryOutputStream( entry ) );
        }
    }

    private String readMessageSegment() throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead = 0;
        for ( ;; bytesRead++ ) {
            byte b = (byte) inputStream.read();
            if ( b == -1 ) return null; // end of stream
            if ( b == ' ' || b == '\n' ) break;
            buffer[bytesRead] = b;
        }
        return new String( buffer, 0, bytesRead, US_ASCII );
    }

    private void writeAck() throws IOException {
        logger.debug( "writing ack" );
        outputStream.write( (byte) 0 );
        outputStream.flush();
    }

    private void writeMessage( String message ) throws IOException {
        writeMessage( message.getBytes( US_ASCII ) );
    }

    private void writeMessage( byte... message ) throws IOException {
        if ( logger.isDebugEnabled() ) {
            logger.debug( "writing message: '{}'", new String( message, US_ASCII ) );
        }
        outputStream.write( message );
        outputStream.flush();
        checkAck();
    }

    private interface CurrentEntry {
        public void complete() throws IOException;

        public boolean isDirectoryEntry();

        public SshPath path();
    }

    private class InputDirectoryEntry implements CurrentEntry {
        private ScpEntry entry;

        private InputDirectoryEntry( ScpEntry entry ) throws IOException {
            this.entry = entry;
            writeAck();
        }

        public void complete() throws IOException {
            writeAck();
        }

        public boolean isDirectoryEntry() {
            return true;
        }

        public SshPath path() {
            return entry.path();
        }
    }

    private class OutputDirectoryEntry implements CurrentEntry {
        private ScpEntry entry;

        private OutputDirectoryEntry( ScpEntry entry ) throws IOException {
            this.entry = entry;
            writeMessage( "D" + entry.mode() + " 0 " + entry.path().getFileName() + "\n" );
        }

        public void complete() throws IOException {
            writeMessage( "E\n" );
        }

        public boolean isDirectoryEntry() {
            return true;
        }

        public SshPath path() {
            return entry.path();
        }
    }

    private class EntryInputStream extends InputStream implements CurrentEntry {
        private ScpEntry entry;
        private long ioCount;
        private boolean closed;

        public EntryInputStream( ScpEntry entry ) throws IOException {
            this.entry = entry;
            this.ioCount = 0L;

            writeAck();
            this.closed = false;
        }

        @Override
        public void close() throws IOException {
            if ( !closed ) {
                if ( !isComplete() ) {
                    throw new IOException( "stream not finished ("
                            + ioCount + "!=" + entry.size() + ")" );
                }
                writeAck();
                checkAck();
                this.closed = true;
            }
        }

        public void complete() throws IOException {
            close();
        }

        private void increment() throws IOException {
            ioCount++;
        }

        private boolean isComplete() {
            return ioCount == entry.size();
        }

        public boolean isDirectoryEntry() {
            return false;
        }

        public SshPath path() {
            return entry.path();
        }

        @Override
        public int read() throws IOException {
            if ( isComplete() ) {
                return -1;
            }
            increment();
            return inputStream.read();
        }
    }

    private class EntryOutputStream extends OutputStream implements CurrentEntry {
        private boolean closed;
        private long ioCount;
        private ScpEntry entry;

        public EntryOutputStream( ScpEntry entry ) throws IOException {
            this.entry = entry;
            this.ioCount = 0L;

            writeMessage( "C" + entry.mode() + " " + entry.size() + " " + entry.path().getFileName() + "\n" );
            this.closed = false;
        }

        @Override
        public void close() throws IOException {
            if ( !closed ) {
                if ( !isComplete() ) {
                    throw new IOException( "stream not finished ("
                            + ioCount + "!=" + entry.size() + ")" );
                }
                writeMessage( (byte) 0 );
                this.closed = true;
            }
        }

        public void complete() throws IOException {
            close();
        }

        private void increment() throws IOException {
            if ( isComplete() ) {
                throw new IOException( "too many bytes written for file " + entry.path().getFileName() );
            }
            ioCount++;
        }

        private boolean isComplete() {
            return ioCount == entry.size();
        }

        public boolean isDirectoryEntry() {
            return false;
        }

        public SshPath path() {
            return entry.path();
        }

        @Override
        public void write( int b ) throws IOException {
            increment();
            outputStream.write( b );
        }
    }
}
