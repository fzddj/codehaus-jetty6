package org.mortbay.jetty.security;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.mortbay.io.Buffer;
import org.mortbay.io.nio.NIOBuffer;
import org.mortbay.io.nio.SelectorManager;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.log.Log;

/* ------------------------------------------------------------ */
/**
 * SslHttpChannelEndPoint.
 * 
 * @author Nik Gonzalez <ngonzalez@exist.com>
 * @author Greg Wilkins <gregw@mortbay.com>
 */
public class SslHttpChannelEndPoint extends SelectChannelConnector.ConnectorEndPoint implements Runnable
{
    private static final ByteBuffer[] __NO_BUFFERS={};
    private static final ByteBuffer __EMPTY=ByteBuffer.allocate(0);
    
    private SSLEngine _engine;
    private ByteBuffer _inBuffer;
    private NIOBuffer _inNIOBuffer;
    private ByteBuffer _outBuffer;
    private NIOBuffer _outNIOBuffer;

    private ByteBuffer _reuseBuffer;    
    private ByteBuffer[] _outBuffers=new ByteBuffer[3];

    // ssl
    protected SSLSession _session;
    
    /* ------------------------------------------------------------ */
    public SslHttpChannelEndPoint(SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, SSLEngine engine)
            throws SSLException, IOException
    {
        super(channel,selectSet,key);

        // ssl
        _engine=engine;
        _engine.setUseClientMode(false);
        _session=engine.getSession();

        // TODO pool buffers and use only when needed.
        
        _outNIOBuffer=new NIOBuffer(_session.getPacketBufferSize(),true);
        _outBuffer=_outNIOBuffer.getByteBuffer();
        _inNIOBuffer=new NIOBuffer(_session.getPacketBufferSize(),true);
        _inBuffer=_inNIOBuffer.getByteBuffer();
    }

    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        _engine.closeOutbound();
        
        try
        {   
            loop: while (isOpen() && !_engine.isOutboundDone() )
            {
                if (_outNIOBuffer.length()>0)
                {
                    flush();
                    Thread.sleep(100); // TODO yuck
                }
                
                switch(_engine.getHandshakeStatus())
                {
                    case FINISHED:
                    case NOT_HANDSHAKING:
                        break loop;
                        
                    case NEED_UNWRAP:
                        if(!fill(__EMPTY))
                            Thread.sleep(100); // TODO yuck
                        break;
                        
                    case NEED_TASK:
                    {
                        Runnable task;
                        while ((task=_engine.getDelegatedTask())!=null)
                        {
                            task.run();
                        }
                        break;
                    }
                        
                    case NEED_WRAP:
                    {
                        if (_outNIOBuffer.length()>0)
                            flush();
                        
                        SSLEngineResult result=null;
                        try
                        {
                            _outNIOBuffer.compact();
                            int put=_outNIOBuffer.putIndex();
                            _outBuffer.position(put);
                            result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                            _outNIOBuffer.setPutIndex(put+result.bytesProduced());
                        }
                        finally
                        {
                            _outBuffer.position(0);
                        }
                        
                        flush();
                        
                        break;
                    }
                }
            }
            
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        catch (InterruptedException e)
        {
            Log.ignore(e);
        }
        finally
        {
            super.close();
        }
        
        
    }

    /* ------------------------------------------------------------ */
    /* 
     */
    public int fill(Buffer buffer) throws IOException
    {
        synchronized(buffer)
        {
            ByteBuffer bbuf=extractInputBuffer(buffer);
            int size=buffer.length();

            try
            {
                fill(bbuf);

                loop: while (_inBuffer.remaining()>0)
                {
                    if (_outNIOBuffer.length()>0)
                        flush();
                    
                    switch(_engine.getHandshakeStatus())
                    {
                        case FINISHED:
                        case NOT_HANDSHAKING:
                            break loop;

                        case NEED_UNWRAP:
                            if(!fill(bbuf))
                                break loop;
                            break;

                        case NEED_TASK:
                        {
                            Runnable task;
                            while ((task=_engine.getDelegatedTask())!=null)
                            {
                                task.run();
                            }
                            break;
                        }

                        case NEED_WRAP:
                        {
                            SSLEngineResult result=null;
                            try
                            {
                                _outNIOBuffer.compact();
                                int put=_outNIOBuffer.putIndex();
                                _outBuffer.position();
                                result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                                _outNIOBuffer.setPutIndex(put+result.bytesProduced());
                            }
                            finally
                            {
                                _outBuffer.position(0);
                            }

                            flush();

                            break;
                        }
                    }
                }
            }
            finally
            {
                buffer.setPutIndex(bbuf.position());
                bbuf.position(0);
            }

            return buffer.length()-size; 
        }
    }

    /* ------------------------------------------------------------ */
    public int flush(Buffer buffer) throws IOException
    {
        return flush(buffer,null,null);
    }


    /* ------------------------------------------------------------ */
    /*     
     */
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        if (_outNIOBuffer.length()>0)
        {
            flush();
            if (_outNIOBuffer.length()>0)
                return 0;
        }
        
        _outBuffers[0]=extractOutputBuffer(header);
        _outBuffers[1]=extractOutputBuffer(buffer);
        _outBuffers[2]=extractOutputBuffer(trailer);

        SSLEngineResult result;
        int consumed=0;
        try
        {
            _outNIOBuffer.clear();
            _outBuffer.position(0);
            _outBuffer.limit(_outBuffer.capacity());
            result=_engine.wrap(_outBuffers,_outBuffer);
            _outNIOBuffer.setGetIndex(0);
            _outNIOBuffer.setPutIndex(result.bytesProduced());
            consumed=result.bytesConsumed();
        }
        finally
        {
            _outBuffer.position(0);
            
            if (consumed>0 && header!=null)
            {
                int len=consumed<header.length()?consumed:header.length();
                header.skip(len);
                consumed-=len;
                _outBuffers[0].position(0);
                _outBuffers[0].limit(_outBuffers[0].capacity());
            }
            if (consumed>0 && buffer!=null)
            {
                int len=consumed<buffer.length()?consumed:buffer.length();
                buffer.skip(len);
                consumed-=len;
                _outBuffers[1].position(0);
                _outBuffers[1].limit(_outBuffers[1].capacity());
            }
            if (consumed>0 && trailer!=null)
            {
                int len=consumed<trailer.length()?consumed:trailer.length();
                trailer.skip(len);
                consumed-=len;
                _outBuffers[1].position(0);
                _outBuffers[1].limit(_outBuffers[1].capacity());
            }
            assert consumed==0;
        }
    
        flush();
        
        return result.bytesConsumed();
    }

    
    /* ------------------------------------------------------------ */
    public void flush() throws IOException
    {
        while (_outNIOBuffer.length()>0)
        {
            int flushed=super.flush(_outNIOBuffer);
            if (flushed==0)
            {
                Thread.yield();
                flushed=super.flush(_outNIOBuffer);
                if (flushed==0)
                    return;
            }
        }
    }

    /* ------------------------------------------------------------ */
    private ByteBuffer extractInputBuffer(Buffer buffer)
    {
        assert buffer instanceof NIOBuffer;
        NIOBuffer nbuf=(NIOBuffer)buffer;
        ByteBuffer bbuf=nbuf.getByteBuffer();
        bbuf.position(buffer.putIndex());
        return bbuf;
    }
    
    /* ------------------------------------------------------------ */
    private ByteBuffer extractOutputBuffer(Buffer buffer)
    {
        if(buffer==null)
            return __EMPTY;
        
        ByteBuffer src=null;
        NIOBuffer nBuf=null;

        if (buffer.buffer() instanceof NIOBuffer)
        {
            nBuf=(NIOBuffer)buffer.buffer();
            src=nBuf.getByteBuffer();
        }
        else
        {
            if (_reuseBuffer == null)
            {
                _reuseBuffer = ByteBuffer.allocateDirect(_session.getPacketBufferSize());
            }
            _reuseBuffer.put(buffer.asArray());
            src = _reuseBuffer;
        }

        if (src!=null)
        {
            src.position(buffer.getIndex());
            src.limit(buffer.putIndex());
        }

        return src;
    }

    /* ------------------------------------------------------------ */
    private boolean fill(ByteBuffer buffer) throws IOException
    {
        int in_len=0;

        if (_inNIOBuffer.hasContent())
            _inNIOBuffer.compact();
        else 
            _inNIOBuffer.clear();

        while (_inNIOBuffer.space()>0)
        {
            int len=super.fill(_inNIOBuffer);
            if (len<=0)
            {
                if (len==0 || in_len>0)
                    break;
                throw new IOException("EOF");
            }
            in_len+=len;
        }
        

        if (_inNIOBuffer.length()==0)
            return false;

        SSLEngineResult result;
        try
        {
            _inBuffer.position(_inNIOBuffer.getIndex());
            _inBuffer.limit(_inNIOBuffer.putIndex());
            result=_engine.unwrap(_inBuffer,buffer);
            _inNIOBuffer.skip(result.bytesConsumed());
        }
        finally
        {
            _inBuffer.position(0);
            _inBuffer.limit(_inBuffer.capacity());
        }

        if (result != null)
        {
            switch(result.getStatus())
            {
                case OK:
                    break;
                case CLOSED:
                    throw new IOException("sslEngine closed");
                    
                case BUFFER_OVERFLOW:
                    Log.debug("unwrap {}",result);
                    break;
                    
                case BUFFER_UNDERFLOW:
                    Log.debug("unwrap {}",result);
                    break;
                    
                default:
                    Log.warn("unwrap "+result);
                throw new IOException(result.toString());
            }
        }
        
        return (result.bytesProduced()+result.bytesConsumed())>0;
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingInput()
    {
        return _inNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingOutput()
    {
        return _outNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferred()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public SSLEngine getSSLEngine()
    {
        return _engine;
    }
}
