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
import java.nio.channels.FileChannel;

public abstract class EncoderBase implements IEncoder
{
    //  Where to get the data to write from.  写入的数据
    private ByteBuffer writeBuf;
    private FileChannel writeChannel;
    private int writePos;

    //  Next step. If set to -1, it means that associated data stream
    //  is dead.  下一步,如果设置为-1 ,意味着相关的数据流已经结束
    private int next;

    //  If true, first byte of the message is being written.   如果为true则第一个字节的消息已经写入
    @SuppressWarnings("unused")
    private boolean beginning;

    //  How much data to write before next step should be executed.  在下一步执行前,有多少消息写入
    private int toWrite;

    //  The buffer for encoded data.  encoded的数据的buffer
    private ByteBuffer buffer;

    private int bufferSize;

    private boolean error;

    protected EncoderBase(int bufferSize)
    {
        this.bufferSize = bufferSize;
        buffer = ByteBuffer.allocateDirect(bufferSize);
        error = false;
    }

    //  The function returns a batch of binary data. The data
    //  are filled to a supplied buffer. If no buffer is supplied (data_
    //  points to NULL) decoder object will provide buffer of its own.

    /**
     * 返回一个批量的二进制数据,数据填充到一个提供的buffer内,如果没有buffer提供,decoder会提供自己的buffer
     * {@inheritDoc}
     */
    @Override
    public Transfer getData(ByteBuffer buffer)
    {
        if (buffer == null) {
            buffer = this.buffer;
        }

        buffer.clear();

        while (buffer.hasRemaining()) {
            //  If there are no more data to return, run the state machine.
            //  If there are still no data, return what we already have
            //  in the buffer.   如果没有数据返回,启动状态机器,如果还是没有数据,返回当前已经存在的buffer
            if (toWrite == 0) {
                //  If we are to encode the beginning of a new message,
                //  adjust the message offset.  如果我们去encode一个新消息的开始,调整消息的offset

                if (!next()) {
                    break;
                }
            }

            //  If there is file channel to send,
            //  send current buffer and the channel together   如果这里存在文件channel去读取,发送当前的buffer和channel一起

            if (writeChannel != null) {
                buffer.flip();
                Transfer t = new Transfer.FileChannelTransfer(buffer, writeChannel,
                                                    (long) writePos, (long) toWrite);
                writePos = 0;
                toWrite = 0;

                return t;
            }
            //  If there are no data in the buffer yet and we are able to
            //  fill whole buffer in a single go, let's use zero-copy.
            //  There's no disadvantage to it as we cannot stuck multiple
            //  messages into the buffer anyway. Note that subsequent
            //  write(s) are non-blocking, thus each single write writes
            //  at most SO_SNDBUF bytes at once not depending on how large
            //  is the chunk returned from here.
            //  As a consequence, large messages being sent won't block
            //  other engines running in the same I/O thread for excessive
            //  amounts of time.
            
            /**
             * 如果还没有数据在buffer中,我们能fill所有的buffer在一次操作中,
             * 使用zero-copy,这样是没有缺点的因为我们无法将多个消息stuck进入buffer.
             * 由于后来的写是非阻塞的,那么美一个单独的写最多能写SO_SNDBUF字节一次,不依赖于这个chunk有多大返回
             * 这个结果,大数据的发送不会block其他engine运行,在同一个i/o线程过多的时间。
             */
            if (this.buffer.position() == 0 && toWrite >= bufferSize) {
                Transfer t = new Transfer.ByteBufferTransfer(writeBuf);
                writePos = 0;
                toWrite = 0;

                return t;
            }

            //  Copy data to the buffer. If the buffer is full, return.  copy数据到buffer,如果buffer满了,就返回
            int remaining = buffer.remaining();
            if (toWrite <= remaining) {
                buffer.put(writeBuf);
                writePos = 0;
                toWrite = 0;
            }
            else {
                writeBuf.limit(writePos + remaining);
                buffer.put(writeBuf);
                writePos += remaining;
                toWrite -= remaining;
                writeBuf.limit(writePos + toWrite);
            }
        }

        buffer.flip();
        return new Transfer.ByteBufferTransfer(buffer);
    }

    @Override
    public boolean hasData()
    {
        return toWrite > 0;
    }

    protected int state()
    {
        return next;
    }

    protected void state(int state)
    {
        next = state;
    }

    protected void encodingError()
    {
        error = true;
    }

    public final boolean isError()
    {
        return error;
    }

    protected abstract boolean next();

    protected void nextStep(Msg msg, int state, boolean beginning)
    {
        if (msg == null) {
            nextStep(null, 0, state, beginning);
        }
        else {
            nextStep(msg.buf(), state, beginning);
        }
    }

    protected void nextStep(byte[] buf, int toWrite,
                            int next, boolean beginning)
    {
        if (buf != null) {
            writeBuf = ByteBuffer.wrap(buf);
            writeBuf.limit(toWrite);
        }
        else {
            writeBuf = null;
        }
        writeChannel = null;
        writePos = 0;
        this.toWrite = toWrite;
        this.next = next;
        this.beginning = beginning;
    }

    protected void nextStep(ByteBuffer buf,
          int next, boolean beginning)
    {
       writeBuf = buf;
       writeChannel = null;
       writePos = buf.position();
       this.toWrite = buf.remaining();
       this.next = next;
       this.beginning = beginning;
    }

    protected void nextStep(FileChannel ch, long pos, long toWrite,
                            int next, boolean beginning)
    {
        writeBuf = null;
        writeChannel = ch;
        writePos = (int) pos;
        this.toWrite = (int) toWrite;
        this.next = next;
        this.beginning = beginning;
    }
}
