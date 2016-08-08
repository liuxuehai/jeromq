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

public class Encoder extends EncoderBase
{
    private static final int SIZE_READY = 0;
    private static final int MESSAGE_READY = 1;

    private Msg inProgress;
    private final byte[] tmpbuf;
    private final ByteBuffer tmpbufWrap;
    private IMsgSource msgSource;

    public Encoder(int bufsize)
    {
        super(bufsize);
        tmpbuf = new byte[10];
        tmpbufWrap = ByteBuffer.wrap(tmpbuf);
        //  Write 0 bytes to the batch and go to messageReady state.  写0个字节给批次,同时到消息准备状态
        nextStep((byte[]) null, 0, MESSAGE_READY, true);
    }

    @Override
    public void setMsgSource(IMsgSource msgSource)
    {
        this.msgSource = msgSource;
    }

    @Override
    protected boolean next()
    {
        switch(state()) {
        case SIZE_READY:
            return sizeReady();
        case MESSAGE_READY:
            return messageReady();
        default:
            return false;
        }
    }

    private boolean sizeReady()
    {
        //  Write message body into the buffer.  写入消息体到buffer
        nextStep(inProgress.buf(),
                MESSAGE_READY, !inProgress.hasMore());
        return true;
    }

    private boolean messageReady()
    {
        //  Destroy content of the old message.
        //inProgress.close ();

        //  Read new message. If there is none, return false.
        //  Note that new state is set only if write is successful. That way
        //  unsuccessful write will cause retry on the next state machine
        //  invocation.

        /*
         *读取新消息,如果没有返回false,新状态设置只用当写成功时,
         *这种不成功的写会引起重试在下一次状态调用 
         */
        if (msgSource == null) {
            return false;
        }

        inProgress = msgSource.pullMsg();
        if (inProgress == null) {
            return false;
        }

        //  Get the message size.
        int size = inProgress.size();

        //  Account for the 'flags' byte.  
        size++;

        //  For messages less than 255 bytes long, write one byte of message size.
        //  For longer messages write 0xff escape character followed by 8-byte
        //  message size. In both cases 'flags' field follows.
        /**
         * 小于255 byte的消息,写一个字节的消息大小,
         * 对于大于255 byte的消息写一个0xff escape 字符,之后是8个字节的消息大小
         * 2中情况都会有一个'flag'标记
         */
        tmpbufWrap.position(0);
        if (size < 255) {
            tmpbufWrap.limit(2);
            tmpbuf[0] = (byte) size;
            tmpbuf[1] = (byte) (inProgress.flags() & Msg.MORE);
            nextStep(tmpbufWrap, SIZE_READY, false);
        }
        else {
            tmpbufWrap.limit(10);
            tmpbuf[0] = (byte) 0xff;
            tmpbufWrap.putLong(1, size);
            tmpbuf[9] = (byte) (inProgress.flags() & Msg.MORE);
            nextStep(tmpbufWrap, SIZE_READY, false);
        }

        return true;
    }
}
