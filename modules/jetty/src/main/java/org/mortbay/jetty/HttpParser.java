// ========================================================================
// Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.jetty;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.mortbay.io.Buffer;
import org.mortbay.io.BufferUtil;
import org.mortbay.io.Buffers;
import org.mortbay.io.ByteArrayBuffer;
import org.mortbay.io.EndPoint;
import org.mortbay.io.View;
import org.mortbay.log.Log;

/* ------------------------------------------------------------------------------- */
/**
 * @author gregw
 */
public class HttpParser implements HttpTokens
{
    // States
    public static final int STATE_START = -11;
    public static final int STATE_FIELD0 = -10;
    public static final int STATE_SPACE1 = -9;
    public static final int STATE_FIELD1 = -8;
    public static final int STATE_SPACE2 = -7;
    public static final int STATE_END0 = -6;
    public static final int STATE_END1 = -5;
    public static final int STATE_FIELD2 = -4;
    public static final int STATE_HEADER = -3;
    public static final int STATE_HEADER_NAME = -2;
    public static final int STATE_HEADER_VALUE = -1;
    public static final int STATE_END = 0;
    public static final int STATE_EOF_CONTENT = 1;
    public static final int STATE_CONTENT = 2;
    public static final int STATE_CHUNKED_CONTENT = 3;
    public static final int STATE_CHUNK_SIZE = 4;
    public static final int STATE_CHUNK_PARAMS = 5;
    public static final int STATE_CHUNK = 6;

    /* ------------------------------------------------------------------------------- */
    protected int _state = STATE_START;
    protected byte _eol;
    protected int _length;
    protected int _contentLength;
    protected int _contentPosition;
    protected int _chunkLength;
    protected int _chunkPosition;

    private Buffers _buffers; // source of buffers
    private EndPoint _endp;
    private Buffer _header; // Buffer for header data (and small _content)
    private Buffer _content; // Buffer for large _content
    private Buffer _buffer; // The current buffer in use (either _header or _content)
    private int _headerBufferSize;
    private int _contentBufferSize;
    private boolean _hasContent = false;
    private EventHandler _handler;
    private View _tok0; // Saved token: header name, request method or response version
    private View _tok1; // Saved token: header value, request URI or response code
    private String _multiLineValue;
    private boolean _response = false;

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     */
    public HttpParser(Buffer buffer, EventHandler handler)
    {
        this._header = buffer;
        this._buffer = buffer;
        this._handler = handler;

        if (buffer != null)
        {
            _tok0 = new View(buffer);
            _tok1 = new View(buffer);
            _tok0.setPutIndex(_tok0.getIndex());
            _tok1.setPutIndex(_tok1.getIndex());
        }
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     * @param headerBufferSize TODO
     * @param contentBufferSize TODO
     */
    public HttpParser(Buffers buffers, EndPoint endp, EventHandler handler, int headerBufferSize, int contentBufferSize)
    {
        _buffers = buffers;
        _endp = endp;
        _handler = handler;
        _headerBufferSize=headerBufferSize;
        _contentBufferSize=contentBufferSize;
    }

    /* ------------------------------------------------------------------------------- */
    public int getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(int state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------------------------- */
    public void setState(int state)
    {
        this._state = state;
        _contentLength = UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state < 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state > 0;
    }

    /* ------------------------------------------------------------------------------- */
    public int getContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------------------------- */
    public String toString(Buffer buf)
    {
        return "state=" + _state + " length=" + _length + " buf=" + buf.hashCode();
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until END state.
     * 
     * @param handler
     * @param source
     * @return parser state
     */
    public void parse() throws IOException
    {
        if (_state==STATE_END)
            reset(false);
        if (_state!=STATE_START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (_state != STATE_END)
            parseNext();
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until END state.
     * 
     * @param handler
     * @param source
     * @return parser state
     */
    public void parseAvailable() throws IOException
    {
        parseNext();
        // continue parsing
        while (_state != STATE_END && _buffer.length() > 0)
        {
            parseNext();
        }
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     *  
     */
    public void parseNext() throws IOException
    {
        if (_buffer==null)
        {
            _header = _buffers.getBuffer(_headerBufferSize);
            _buffer = _header;
            _tok0 = new View(_header);
            _tok1 = new View(_header);
            _tok0.setPutIndex(_tok0.getIndex());
            _tok1.setPutIndex(_tok1.getIndex());
        }
        
        if (_state == STATE_END) throw new IllegalStateException("STATE_END");
        if (_state == STATE_CONTENT && _contentPosition == _contentLength)
        {
            _state = STATE_END;
            _handler.messageComplete(_contentPosition);
            return;
        }
        
        int length=_buffer.length();

        // Fill buffer if we can
        if (length == 0)
        {
            if (_buffer.markIndex() == 0 && _buffer.putIndex() == _buffer.capacity())
                    throw new IOException("FULL");
            int filled = -1;
            if (_endp != null)
            {

                // Compress buffer if handling _content buffer
                // TODO check this is not moving data too much
                if (_buffer == _content) _buffer.compact();

                if (_buffer.space() == 0) throw new IOException("FULL");
                
                try
                {
                    filled = _endp.fill(_buffer);
                }
                catch(IOException ioe)
                {
                    Log.debug(ioe);
                    throw new IOException("EOF");
                }
            }
            
            if (filled < 0 && _state == STATE_EOF_CONTENT)
            {
                _state = STATE_END;
                _handler.messageComplete(_contentPosition);
                return;
            }
            if (filled < 0) throw new IOException("EOF");
            length=_buffer.length();
        }

        // EventHandler header
        byte ch;
        while (_state < STATE_END && length-->0)
        {
            ch = _buffer.get();
            
            if (_eol == CARRIAGE_RETURN && ch == LINE_FEED)
            {
                _eol = LINE_FEED;
                continue;
            }
            _eol = 0;
            switch (_state)
            {
                case STATE_START:
                    _contentLength = UNKNOWN_CONTENT;
                    if (ch > SPACE)
                    {
                        _buffer.mark();
                        _state = STATE_FIELD0;
                    }
                    break;

                case STATE_FIELD0:
                    if (ch == SPACE)
                    {
                        _tok0.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                        _state = STATE_SPACE1;
                        continue;
                    }
                    else if (ch < SPACE)
                    {
                        throw new IOException("BAD");
                    }
                    break;

                case STATE_SPACE1:
                    if (ch > SPACE)
                    {
                        _buffer.mark();
                        _state = STATE_FIELD1;
                        _response = ch >= '1' && ch <= '5';
                    }
                    else if (ch < SPACE)
                    {
                        throw new IOException("BAD");
                    }
                    break;

                case STATE_FIELD1:
                    if (ch == SPACE)
                    {
                        _tok1.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                        _state = STATE_SPACE2;
                        continue;
                    }
                    else if (ch < SPACE)
                    {
                        // HTTP/0.9
                        _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _buffer
                                .sliceFromMark(), null);
                        _state = STATE_END;
                        _handler.headerComplete();
                        _handler.messageComplete(_contentPosition);
                        return;
                    }
                    break;

                case STATE_SPACE2:
                    if (ch > SPACE)
                    {
                        _buffer.mark();
                        _state = STATE_FIELD2;
                    }
                    else if (ch < SPACE)
                    {
                        // HTTP/0.9
                        _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _tok1, null);
                        _state = STATE_END;
                        _handler.headerComplete();
                        _handler.messageComplete(_contentPosition);
                        return;
                    }
                    break;

                case STATE_FIELD2:
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                    {
                        if (_response)
                            _handler.startResponse(HttpVersions.CACHE.lookup(_tok0), BufferUtil
                                    .toInt(_tok1), _buffer.sliceFromMark());
                        else
                            _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _tok1,
                                    HttpVersions.CACHE.lookup(_buffer.sliceFromMark()));
                        _eol = ch;
                        _state = STATE_HEADER;
                        _tok0.setPutIndex(_tok0.getIndex());
                        _tok1.setPutIndex(_tok1.getIndex());
                        _multiLineValue = null;
                        return;
                    }
                    break;

                case STATE_HEADER:

                    if (ch == COLON || ch == SPACE || ch == TAB)
                    {
                        // header value without name - continuation?
                        _length = -1;
                        _state = STATE_HEADER_VALUE;
                    }
                    else
                    {
                        // handler last header if any
                        if (_tok0.length() > 0 || _tok1.length() > 0 || _multiLineValue != null)
                        {
                            Buffer header = HttpHeaders.CACHE.lookup(_tok0);
                            Buffer value = _multiLineValue == null ? (Buffer) _tok1
                                    : (Buffer) new ByteArrayBuffer(_multiLineValue);

                            int ho = HttpHeaders.CACHE.getOrdinal(header);
                            if (ho >= 0)
                            {
                                int vo = -1; 

                                switch (ho)
                                {
                                    case HttpHeaders.CONTENT_LENGTH_ORDINAL:
                                        if (_contentLength != CHUNKED_CONTENT)
                                        {
                                            _contentLength = BufferUtil.toInt(value);
                                            if (_contentLength <= 0)
                                                    _contentLength = HttpParser.NO_CONTENT;
                                        }
                                        break;

                                    case HttpHeaders.CONNECTION_ORDINAL:
                                        // TODO comma list of connections !!!
                                        value = HttpHeaderValues.CACHE.lookup(value);
                                        break;

                                    case HttpHeaders.TRANSFER_ENCODING_ORDINAL:
                                        value = HttpHeaderValues.CACHE.lookup(value);
                                        vo=HttpHeaderValues.CACHE.getOrdinal(value);
                                        if (HttpHeaderValues.CHUNKED_ORDINAL == vo)
                                            _contentLength = CHUNKED_CONTENT;
                                        else
                                        {
                                            // TODO avoid string conversion here
                                            String c = value.toString();
                                            if (c.endsWith(HttpHeaderValues.CHUNKED))
                                                _contentLength = CHUNKED_CONTENT;
                                            else if (c.indexOf(HttpHeaderValues.CHUNKED) >= 0)
                                                    throw new IOException("BAD");
                                        }
                                        break;

                                    case HttpHeaders.CONTENT_TYPE_ORDINAL:
                                        _hasContent = true;
                                        break;
                                }
                            }

                            _handler.parsedHeader(header, value);
                            _tok0.setPutIndex(_tok0.getIndex());
                            _tok1.setPutIndex(_tok1.getIndex());
                            _multiLineValue = null;
                        }

                        
                        // now handle ch
                        if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                        {
                            // End of header

                            // work out the _content demarcation
                            if (_contentLength == UNKNOWN_CONTENT)
                            {
                                if (_hasContent || _response)
                                    _contentLength = EOF_CONTENT;
                                else
                                    _contentLength = NO_CONTENT;
                            }

                            _contentPosition = 0;
                            _eol = ch;
                            switch (_contentLength)
                            {
                                case HttpParser.EOF_CONTENT:
                                    _state = STATE_EOF_CONTENT;
                                    if(_buffers!=null && _buffer==_header)
                                    {
                                        _buffer=_buffers.getBuffer(_contentBufferSize);
                                        _buffer.put(_header); 
                                        _header.clear();
                                        
                                    }
                                    _handler.headerComplete(); // May recurse here !
                                    break;
                                case HttpParser.CHUNKED_CONTENT:
                                    _state = STATE_CHUNKED_CONTENT;
                                	if(_buffers!=null && _buffer==_header)
                                	{
                                	    _buffer=_buffers.getBuffer(_contentBufferSize);
                                	    _buffer.put(_header); 
                                        _header.clear();
                                	}
                                    _handler.headerComplete(); // May recurse here !
                                    break;
                                case HttpParser.NO_CONTENT:
                                    _state = STATE_END;
                                    _handler.headerComplete(); // May recurse here !
                                    _handler.messageComplete(_contentPosition);
                                    break;
                                default:
                                    _state = STATE_CONTENT;

                                	if(_buffers!=null && _buffer==_header && _contentLength>_buffer.capacity()-_buffer.getIndex())
                                    {
                                	    _buffer=_buffers.getBuffer(_contentBufferSize);
                                	    _buffer.put(_header); 
                                        _header.clear();
                                    }
                                    _handler.headerComplete(); // May recurse here !
                                    break;
                            }
                            return;
                        }
                        else
                        {
                            // New header
                            _length = 1;
                            _buffer.mark();
                            _state = STATE_HEADER_NAME;
                        }
                    }
                    break;

                case STATE_HEADER_NAME:
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                    {
                        if (_length > 0)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                        _eol = ch;
                        _state = STATE_HEADER;
                    }
                    else if (ch == COLON)
                    {
                        if (_length > 0)
                                _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                        _length = -1;
                        _state = STATE_HEADER_VALUE;
                    }
                    else if (ch != SPACE && ch != TAB)
                    {
                        if (_length == -1) _buffer.mark();
                        _length = _buffer.getIndex() - _buffer.markIndex();
                    }
                    break;

                case STATE_HEADER_VALUE:
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                    {
                        if (_length > 0)
                        {
                            if (_tok1.length() == 0)
                                _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                            else
                            {
                                // Continuation line!
                                // TODO - deal with CR LF and COLON?
                                if (_multiLineValue == null) _multiLineValue = _tok1.toString();
                                _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                _multiLineValue += " " + _tok1.toString();
                            }
                        }
                        _eol = ch;
                        _state = STATE_HEADER;
                    }
                    else if (ch != SPACE && ch != TAB)
                    {
                        if (_length == -1) _buffer.mark();
                        _length = _buffer.getIndex() - _buffer.markIndex();
                    }
                    break;
            }
        } // end of HEADER states loop

        
        
        // Handle _content
        Buffer chunk;
        length = _buffer.length();
        while (_state > STATE_END && length > 0)
        {
            if (_eol == CARRIAGE_RETURN && _buffer.peek() == LINE_FEED)
            {
                _eol = _buffer.get();
                length = _buffer.length();
                continue;
            }
            _eol = 0;
            switch (_state)
            {
                case STATE_EOF_CONTENT:
                    chunk = _buffer.get(_buffer.length());
                    _handler.content(_contentPosition, chunk);
                    _contentPosition += chunk.length();
                    return;

                case STATE_CONTENT: 
                {
                    int remaining = _contentLength - _contentPosition;
                    if (remaining == 0)
                    {
                        _state = STATE_END;
                        _handler.messageComplete(_contentPosition);
                        return;
                    }
                    else if (length > remaining) length = remaining;
                    chunk = _buffer.get(length);
                    _handler.content(_contentPosition, chunk);
                    _contentPosition += chunk.length();
                    return;
                }

                case STATE_CHUNKED_CONTENT:
                {
                    ch = _buffer.peek();
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                        _eol = _buffer.get();
                    else if (ch <= SPACE)
                        _buffer.get();
                    else
                    {
                        _chunkLength = 0;
                        _chunkPosition = 0;
                        _state = STATE_CHUNK_SIZE;
                    }
                    break;
                }

                case STATE_CHUNK_SIZE:
                {
                    ch = _buffer.get();
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                    {
                        _eol = ch;
                        if (_chunkLength == 0)
                        {
                            _state = STATE_END;
                            _handler.messageComplete(_contentPosition);
                            return;
                        }
                        else
                            _state = STATE_CHUNK;
                    }
                    else if (ch <= SPACE || ch == SEMI_COLON)
                        _state = STATE_CHUNK_PARAMS;
                    else if (ch >= '0' && ch <= '9')
                        _chunkLength = _chunkLength * 16 + (ch - '0');
                    else if (ch >= 'a' && ch <= 'f')
                        _chunkLength = _chunkLength * 16 + (10 + ch - 'a');
                    else if (ch >= 'A' && ch <= 'F')
                        _chunkLength = _chunkLength * 16 + (10 + ch - 'A');
                    else
                        throw new IOException("bad chunk char: " + ch);
                    break;
                }

                case STATE_CHUNK_PARAMS:
                {
                    ch = _buffer.get();
                    if (ch == CARRIAGE_RETURN || ch == LINE_FEED)
                    {
                        _eol = ch;
                        if (_chunkLength == 0)
                        {
                            _state = STATE_END;
                            _handler.messageComplete(_contentPosition);
                            return;
                        }
                        else
                            _state = STATE_CHUNK;
                    }
                    break;
                }
                
                case STATE_CHUNK: 
                {
                    int remaining = _chunkLength - _chunkPosition;
                    if (remaining == 0)
                    {
                        _state = STATE_CHUNKED_CONTENT;
                        break;
                    }
                    else if (length > remaining) 
                        length = remaining;
                    chunk = _buffer.get(length);
                    _handler.content(_contentPosition, chunk);
                    _contentPosition += chunk.length();
                    _chunkPosition += chunk.length();
                    return;
                }
            }

            length = _buffer.length();
        }
    }

    /* ------------------------------------------------------------------------------- */
    public void reset(boolean returnBuffers)
    {
        _state = STATE_START;
        _contentLength = UNKNOWN_CONTENT;
        _contentPosition = 0;
        _length = 0;
        _hasContent = false;
        _response = false;
        
        if (_buffer!=null)
        {
            
            if ( _buffer==_content && _content.length()<=_header.space())
            {
                _buffer=_header;
                if (_content.length()>0)
                {
                    if (_eol == CARRIAGE_RETURN && _content.peek() == LINE_FEED)
                    {
                        _content.skip(1);
                        _eol = LINE_FEED;
                    }
                    _header.put(_content);
                }
                if (_buffers!=null && returnBuffers)
                    _buffers.returnBuffer(_content);
                _content=null;
            }
            else if (_buffer.length()>0 && _eol == CARRIAGE_RETURN && _buffer.peek() == LINE_FEED)
            {
                _buffer.skip(1);
                _eol = LINE_FEED;
                
            }
            
            if (!_header.hasContent() && _buffers!=null && returnBuffers)
            {
                _buffers.returnBuffer(_header);
                _header=null;
                _buffer=null;
            }   
            else
            {
                _buffer.compact();
                _tok0.update(_buffer);
                _tok0.update(0,0);
                _tok1.update(_buffer);
                _tok1.update(0,0);
            }
        }
    }

    public static abstract class EventHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startRequest(Buffer method, Buffer url, Buffer version)
                throws IOException;

        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startResponse(Buffer version, int status, Buffer reason)
                throws IOException;

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         */
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
        }

        public void headerComplete() throws IOException
        {
        }

        public void content(int index, Buffer ref) throws IOException
        {
        }

        public void messageComplete(int contextLength) throws IOException
        {
        }
    }
}