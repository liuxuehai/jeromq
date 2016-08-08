/*
    Copyright (c) 2007-2014 Contributors as noted in the AUTHORS file

    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package zmq;

import java.nio.ByteBuffer;

//  Helper base class for decoders that know the amount of data to read
//  in advance at any moment. Knowing the amount in advance is a property
//  of the protocol used. 0MQ framing protocol is based size-prefixed
//  paradigm, which qualifies it to be parsed by this class.
//  On the other hand, XML-based transports (like XMPP or SOAP) don't allow
//  for knowing the size of data to read in advance and should use different
//  decoding algorithms.
//
//  This class implements the state machine that parses the incoming buffer.
//  Derived class should implement individual state machine actions.

/**
 * decoders 的基础helper类,知道在任何时候提前阅读的数据量。
 * 提前知道所使用的协议的数量是一个属性。0mq框架协议的基础尺寸前缀范式，是它被这类解析。
 * 另一方面，基于XML的传输（如XMPP或肥皂）不允许了解数据的大小预先阅读和使用不同的译码算法。
 * 该类实现了状态机的分析输入缓冲区。派生类应实现单独的状态机操作。
 * @since 1.0.0
 * @version $Id$
 */
public abstract class DecoderBase implements IDecoder
{
    //  Where to store the read data.  数据存储的地方
    private ByteBuffer readBuf;
    private MsgAllocator msgAllocator = new MsgAllocatorHeap();

    //  The buffer for data to decode.   解析数据的大小
    private int bufsize;
    private ByteBuffer buf;

    private int state;

    boolean zeroCopy;

    public DecoderBase(int bufsize)
    {
        state = -1;
        this.bufsize = bufsize;
        if (bufsize > 0) {
            buf = ByteBuffer.allocateDirect(bufsize);
        }
        readBuf = null;
        zeroCopy = false;
    }

    //  Returns a buffer to be filled with binary data.
    /**
     * 反馈一个缓存,保存2进制数据
     * {@inheritDoc}
     */
    public ByteBuffer getBuffer()
    {
        //  If we are expected to read large message, we'll opt for zero-
        //  copy, i.e. we'll ask caller to fill the data directly to the
        //  message. Note that subsequent read(s) are non-blocking, thus
        //  each single read reads at most SO_RCVBUF bytes at once not
        //  depending on how large is the chunk returned from here.
        //  As a consequence, large messages being received won't block
        //  other engines running in the same I/O thread for excessive
        //  amounts of time.
        // 如果我们想读取大量的数据,我们会选择零拷贝,例如,我们请求调用者直接将数据填满到消息,
        // 注意随后的读是非阻塞的,这样没一个读最多SO_RCVBUF 字节一次 不依赖于这个块返回多少
        // 这样,大消息的在接收的过程中不会阻挡其他engine允许在相同的i/o线程在打了的时间上
        if (readBuf.remaining() >= bufsize) {
            zeroCopy = true;
            return readBuf.duplicate();
        }
        else {
            zeroCopy = false;
            buf.clear();
            return buf;
        }
    }

    //  Processes the data in the buffer previously allocated using
    //  get_buffer function. size_ argument specifies nemuber of bytes
    //  actually filled into the buffer. Function returns number of
    //  bytes actually processed.
    
    public int processBuffer(ByteBuffer buf, int size)
    {
        //  Check if we had an error in previous attempt.
        if (state() < 0) {
            return -1;
        }

        //  In case of zero-copy simply adjust the pointers, no copying
        //  is required. Also, run the state machine in case all the data
        //  were processed.
        // 未防止零拷贝 只是调整指针,没有复制是需要的,允许状态,在所有数据都被处理的情况下
        if (zeroCopy) {
            readBuf.position(readBuf.position() + size);

            while (readBuf.remaining() == 0) {
                if (!next()) {
                    if (state() < 0) {
                        return -1;
                    }
                    return size;
                }
            }
            return size;
        }

        int pos = 0;
        while (true) {
            //  Try to get more space in the message to fill in.
            //  If none is available, return.
            // 尝试在消息中获得更多的空间来填充,如果没有可用的,就返回
            while (readBuf.remaining() == 0) {
                if (!next()) {
                    if (state() < 0) {
                        return -1;
                    }

                    return pos;
                }
            }

            //  If there are no more data in the buffer, return.
            // 如果没有消息,就返回
            if (pos == size) {
                return pos;
            }

            //  Copy the data from buffer to the message.
            // 从缓存中复制数据到消息
            int toCopy = Math.min(readBuf.remaining(), size - pos);
            int limit = buf.limit();
            buf.limit(buf.position() + toCopy);
            readBuf.put(buf);
            buf.limit(limit);
            pos += toCopy;
        }
    }

    protected void nextStep(Msg msg, int state)
    {
        nextStep(msg.buf(), state);
    }

    protected void nextStep(byte[] buf, int toRead, int state)
    {
        readBuf = ByteBuffer.wrap(buf);
        readBuf.limit(toRead);
        this.state = state;
    }

    protected void nextStep(ByteBuffer buf, int state)
    {
        readBuf = buf;
        this.state = state;
    }

    protected int state()
    {
        return state;
    }

    protected void state(int state)
    {
        this.state = state;
    }

    protected void decodingError()
    {
        state(-1);
    }

    //  Returns true if the decoder has been fed all required data
    //  but cannot proceed with the next decoding step.
    //  False is returned if the decoder has encountered an error.
    /**
     * 如果decoder已经fed所有需要的数据,就返回true
     * 但是不能出来下一步的,出错就返回false
     * {@inheritDoc}
     */
    @Override
    public boolean stalled()
    {
        //  Check whether there was decoding error.
        if (!next()) {
            return false;
        }

        while (readBuf.remaining() == 0) {
            if (!next()) {
                return next();
            }
        }
        return false;
    }

    public MsgAllocator getMsgAllocator()
    {
       return msgAllocator;
    }

    public void setMsgAllocator(MsgAllocator msgAllocator)
    {
       this.msgAllocator = msgAllocator;
    }

    protected abstract boolean next();
}
