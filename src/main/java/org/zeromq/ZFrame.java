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

package org.zeromq;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import org.zeromq.ZMQ.Socket;

/**
 * ZFrame
 *
 * The ZFrame class provides methods to send and receive single message
 * frames across 0MQ sockets. A 'frame' corresponds to one underlying zmq_msg_t in the libzmq code.
 * When you read a frame from a socket, the more() method indicates if the frame is part of an
 * unfinished multipart message.  The send() method normally destroys the frame, but with the ZFRAME_REUSE flag, you can send
 * the same frame many times. Frames are binary, and this class has no special support for text data.
 *
 *  ZFrame 提供方法通过OMQ sockets 发送和接收单个消息frame,
 *  一个 frame对应于一个底层的zmq_msg_t在libznq中,当你通过一个socket读取一个frame时,
 *  more()方法指明这个frame是否为一个未结束的消息的一部分.
 *  send()方式正常的销毁frame,如果有ZFRAME_REUSE标记,你可以发送这个frame多次.
 *  Frame是2进制的,对于text数据病没有特殊处理
 */

public class ZFrame
{
    public static final int MORE  =   ZMQ.SNDMORE;
    public static final int REUSE =   128;     // no effect at java
    public static final int DONTWAIT = ZMQ.DONTWAIT;

    private boolean more;

    private byte[] data;

    /**
     * Class Constructor
     * Creates an empty frame.
     * (Useful when reading frames from a 0MQ Socket)
     * 
     * 创建一个空方frame,(用于通过OMQ Socket读取数据)
     */
    protected ZFrame()
    {
    }

    /**
     * Class Constructor
     * Copies message data into ZFrame object
     * 
     * 复制消息数据到ZFrame对象
     * @param data
     *          Data to copy into ZFrame object
     */
    public ZFrame(byte[] data)
    {
        if (data != null) {
            this.data = data;
        }
    }

    /**
     * Class Constructor
     * Copies String into frame data
     * 
     * 复制消息数据到ZFrame对象
     * @param data
     */
    public ZFrame(String data)
    {
        if (data != null) {
            this.data = data.getBytes(ZMQ.CHARSET);
        }
    }

    /**
     * Destructor.
     */
    public void destroy()
    {
        if (hasData()) {
            data = null;
        }
    }

    /**
     * @return the data
     */
    public byte[] getData()
    {
        return data;
    }

    /**
     * more标记,如果为true,则表明还有更多消息存在
     * @return More flag, true if last read had MORE message parts to come
     *      
     */
    public boolean hasMore()
    {
        return more;
    }

    /**
     * Returns byte size of frame, if set, else 0
     * 返回frame的byte 大小,如果没有则为0
     * @return
     *          Number of bytes in frame data, else 0
     */
    public int size()
    {
        if (hasData()) {
            return data.length;
        }
        else {
            return 0;
        }
    }

    /**
     * Convenience method to ascertain if this frame contains some message data
     * 
     * 表明frame是否存在消息数据
     * @return
     *          True if frame contains data
     */
    public boolean hasData()
    {
        return data != null;
    }

    /**
     * Internal method to call org.zeromq.Socket send() method.
     * 
     * 
     * @param socket
     *          0MQ socket to send on
     * @param flags
     *          Valid send() method flags, defined in org.zeromq.ZMQ class
     * @return
     *          True if success, else False
     */
    public boolean send(Socket socket, int flags)
    {
        if (socket == null) {
            throw new IllegalArgumentException("socket parameter must be set");
        }

        return socket.send(data, flags);
    }

    /**
     * Sends frame to socket if it contains any data.
     * Frame contents are kept after the send.
     * 
     * 
     * @param socket
     *          0MQ socket to send frame
     * @param flags
     *          Valid send() method flags, defined in org.zeromq.ZMQ class
     * @return
     *          True if success, else False
     */
    public boolean sendAndKeep(Socket socket, int flags)
    {
        return send(socket, flags);
    }

    /**
     * Sends frame to socket if it contains any data.
     * Frame contents are kept after the send.
     * Uses default behaviour of Socket.send() method, with no flags set
     * @param socket
     *          0MQ socket to send frame
     * @return
     *          True if success, else False
     */
    public boolean sendAndKeep(Socket socket)
    {
        return sendAndKeep(socket, 0);
    }

    /**
     * Sends frame to socket if it contains data.
     * Use this method to send a frame and destroy the data after.
     * 
     * 消息发送后就销毁数据
     * @param socket
     *          0MQ socket to send frame
     * @param flags
     *          Valid send() method flags, defined in org.zeromq.ZMQ class
     * @return
     *          True if success, else False
     */
    public boolean sendAndDestroy(Socket socket, int flags)
    {
        boolean ret = send(socket, flags);
        if (ret) {
            destroy();
        }
        return ret;
    }

    /**
     * Sends frame to socket if it contains data.
     * Use this method to send an isolated frame and destroy the data after.
     * Uses default behaviour of Socket.send() method, with no flags set
     * @param socket
     *          0MQ socket to send frame
     * @return
     *          True if success, else False
     */
    public boolean sendAndDestroy(Socket socket)
    {
        return sendAndDestroy(socket, 0);
    }

    /**
     * Creates a new frame that duplicates an existing frame
     * 创建一个新的frame,复制当前frame的数据
     * 
     * @return
     *          Duplicate of frame; message contents copied into new byte array
     */
    public ZFrame duplicate()
    {
        return new ZFrame(this.data);
    }

    /**
     * Returns true if both frames have byte - for byte identical data
     * 
     * 判断2个frame是否是相同的数据
     * @param other
     *          The other ZFrame to compare
     * @return
     *          True if both ZFrames have same byte-identical data, else false
     */
    public boolean hasSameData(ZFrame other)
    {
        if (other == null) {
            return false;
        }

        if (size() == other.size()) {
            return Arrays.equals(data, other.data);
        }
        return false;
    }

    /**
     * Sets new contents for frame
     * 为frame重新设置数据
     * @param data
     *          New byte array contents for frame
     */
    public void reset(String data)
    {
        this.data = data.getBytes(ZMQ.CHARSET);
    }

    /**
     * Sets new contents for frame
     * 为frame重新设置数据
     * @param data
     *          New byte array contents for frame
     */
    public void reset(byte[] data)
    {
        this.data = data;
    }

    /**
     * frame数据的16进制string
     * @return frame data as a printable hex string
     */
    public String strhex()
    {
        String hexChar = "0123456789ABCDEF";

        StringBuilder b = new StringBuilder();
        for (byte aData : data) {
            int b1 = aData >>> 4 & 0xf;
            int b2 = aData & 0xf;
            b.append(hexChar.charAt(b1));
            b.append(hexChar.charAt(b2));
        }
        return b.toString();
    }

    /**
     * String equals.
     * Uses String compareTo for the comparison (lexigraphical)
     * 
     * @param str
     *          String to compare with frame data
     * @return
     *          True if frame body data matches given string
     */
    public boolean streq(String str)
    {
        if (!hasData()) {
            return false;
        }
        return new String(this.data, ZMQ.CHARSET).compareTo(str) == 0;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ZFrame zFrame = (ZFrame) o;
        return Arrays.equals(data, zFrame.data);
    }

    @Override
    public int hashCode()
    {
        return Arrays.hashCode(data);
    }

    /**
     * Returns a human - readable representation of frame's data
     * 返回一个易读的frame数据
     * @return
     *          A text string or hex-encoded string if data contains any non-printable ASCII characters
     */
    public String toString()
    {
        if (!hasData()) {
            return "";
        }
        // Dump message as text or hex-encoded string
        boolean isText = true;
        for (byte aData : data) {
            if (aData < 32) {
                isText = false;
                break;
            }
        }
        if (isText) {
            return new String(data, ZMQ.CHARSET);
        }
        else {
            return strhex();
        }
    }

    /**
     * Internal method to call recv on the socket.
     * Does not trap any ZMQExceptions but expects caling routine to handle them.
     * 
     * 内部方法通过socket recv数据
     * 
     * @param socket
     *          0MQ socket to read from
     * @return
     *          byte[] data
     */
    private byte[] recv(Socket socket, int flags)
    {
        if (socket == null) {
            throw new IllegalArgumentException("socket parameter must not be null");
        }

        data = socket.recv(flags);
        more = socket.hasReceiveMore();
        return data;
    }

    /**
     * Receives single frame from socket, returns the received frame object, or null if the recv
     * was interrupted. Does a blocking recv, if you want to not block then use
     * recvFrame(socket, ZMQ.DONTWAIT);
     *
     * 获取一个单独的frame通过socket,返回获取的frame对象,或者null如果recv被中断
     * 这是一block的recv,如果想不block使用recvFrame(socket, ZMQ.DONTWAIT)
     * @param   socket
     *              Socket to read from
     * @return
     *              received frame, else null
     */
    public static ZFrame recvFrame(Socket socket)
    {
        return ZFrame.recvFrame(socket, 0);
    }

    /**
     * Receive a new frame off the socket, Returns newly-allocated frame, or
     * null if there was no input waiting, or if the read was interrupted.
     * 
     * 获取一个frame 通过socket,返回一个新分配的frame,或者null如果没有输入等待,或者read被中断
     * @param   socket
     *              Socket to read from
     * @param   flags
     *              Pass flags to 0MQ socket.recv call
     * @return
     *              received frame, else null
     */
    public static ZFrame recvFrame(Socket socket, int flags)
    {
        ZFrame f = new ZFrame();
        byte [] data = f.recv(socket, flags);
        if (data == null) {
            return null;
        }
        return f;
    }

    public void print(String prefix)
    {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        if (prefix != null) {
            pw.printf("%s", prefix);
        }
        byte []data = getData();
        int size = size();

        boolean isBin = false;
        int charNbr;
        for (charNbr = 0; charNbr < size; charNbr++) {
            if (data[charNbr] < 9 || data[charNbr] > 127) {
                isBin = true;
            }
        }

        pw.printf("[%03d] ", size);
        int maxSize = isBin ? 35 : 70;
        String elipsis = "";
        if (size > maxSize) {
            size = maxSize;
            elipsis = "...";
        }
        for (charNbr = 0; charNbr < size; charNbr++) {
            if (isBin) {
                pw.printf("%02X", data[charNbr]);
            }
            else {
                pw.printf("%c", data[charNbr]);
            }
        }
        pw.printf("%s\n", elipsis);
        pw.flush();
        pw.close();
        try {
            sw.close();
        }
        catch (IOException e) {
        }

        System.out.print(sw.toString());
    }
}
