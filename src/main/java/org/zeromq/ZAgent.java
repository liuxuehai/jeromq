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
import java.nio.channels.Selector;
import java.util.Arrays;

import org.zeromq.ZMQ.Socket;

/**
 * First implementation of an agent for a remotely controlled background service for 0MQ.
 * Used in conjunction with a ZStar, but not mandatory.
 * 
 * agent的一个实现,被0MQ远程控制的一个后台服务
 *<p>
 * An agent is a mechanism allowing to send messages from one thread to another, and to receive messages from this other thread.
 * 一个agent是允许从一个线程发送消息给另一个线程,并且获取消息从另一个线程,即线程间互通消息
 *<p>
 * Its built-in implementation provides an easy communication-lock
 * system that will close the access once the remote thread is finished.
 * 
 * 它的内部实现是提供一个简单的通讯锁
 * 当远程线程结束活,系统会关闭这个通讯
 *<p>
 * Are proposed for you a restrained set of simple but powerful messaging commands for a quick learning curve
 * and an access to the underlying Socket for advanced usage.
 * 提出你内敛的一套简单但功能强大的短信指令进行快速的学习曲线
    并为高级使用底层套接字的访问。
 * 
 */
// agent for a remote controlled background message processing API for 0MQ.
// contract to be agent of a star
// 代理远程控制的底层消息处理的API0MQ,
public interface ZAgent
{
    /**
     * Receives a control message sent from the Plateau in the Corbeille.
     * The call is blocking.
     * 获取消息
     * 该方法为block
     * @return the received message or null if the context was shut down.
     */
    ZMsg recv();

    /**
     * Receives a control message sent from the Plateau in the Corbeille.
     * The call is blocking depending on the parameter.
     * 该方法block可以设置
     * @param wait   true to make a blocking call, false to not wait, and possibly return null
     * @return the received message or null if the context was shut down or if no message if not blocking.
     */
    ZMsg recv(boolean wait);

    /**
     * Sends a control message from the Corbeille to the Plateau.
     * 发送消息
     * @param message    the message to send
     * @return true if the message was sent, otherwise false (if the distant Star is dead for example)
     */
    boolean send(ZMsg message);

    /**
     * Sends a control message from Corbeille side to the Plateau side.
     * 发送消息
     * @param msg       the message to send
     * @param destroy   true to destroy the message after sending it.
     * @return true if the message was sent, otherwise false (if the distant Star is dead for example)
     */
    boolean send(ZMsg msg, boolean destroy);

    /**
     * Sends a control message from the Corbeille to the Plateau side.
     *
     * @param word    the message to send
     * @return true if the message was sent, otherwise false (if the distant Star is dead for example)
     */
    boolean send(String word);

    /**
     * Sends a control message from the Corbeille to the Plateau side.
     * 发送消息
     * @param word    the message to send
     * @param more   true to send more strings in a single message
     * @return true if the message was sent, otherwise false (if the distant Star is dead for example)
     */
    boolean send(String word, boolean more);

    /**
     * Gives a sign if the distant Star is here.
     * 如果远程star在本地给一个标记
     * @return true if here, otherwise false
     */
    boolean sign();

    /**
     * Forcely destroys the Star.
     * @deprecated not sure it is useful or recommended
     */
    @Deprecated
    void nova();

    /**
     * Returns the socket used for communication.
     * For advanced usage.
     * 为了其他用途,返回一个通讯的socket
     * @return the socket used to communicate with the distant Star.
     */
    Socket pipe();

    public static class Creator
    {
        public static ZAgent create(Socket pipe, String lock)
        {
            return new SimpleAgent(pipe, lock);
        }
    }

    /**
     * Creates a very simple agent with an easy lock mechanism.
     */
    public static final class SimpleAgent implements ZAgent
    {
        // the pipe used for communicating with the star  用于和star进行通讯
        private final Socket pipe;

        // the key used to lock the agent. 关键字用于lock这个代理
        private final byte[] lock;

        // the locked state. lock状态
        private boolean locked;

        /**
         * Creates a new simple agent.
         *
         * @param pipe   the pipe used to send control messages to the distant IStar.
         * @param lock   the lock to use. If null, the locking mechanism is omitted.
         */
        public SimpleAgent(Socket pipe, String lock)
        {
            this.pipe = pipe;
            this.lock = lock == null ? null : lock.getBytes(ZMQ.CHARSET);
        }

        @Override
        public boolean sign()
        {
            return !locked;
        }

        @Override
        public ZMsg recv()
        {
            return recv(true);
        }

        @Override
        public ZMsg recv(boolean wait)
        {
            if (locked) {
                return null;
            }
            try {
                ZMsg msg = ZMsg.recvMsg(pipe, wait ? 0 : ZMQ.DONTWAIT);
                if (msg == null) {
                    return null;
                }

                final ZFrame frame = msg.peek();
                byte[] key = frame.getData();
                if (lock != null && Arrays.equals(lock, key)) {
                    locked = true;
                    // this is the last message anyway, and not a one for a public display
                    //这是最后的消息,不能显出
                    msg = null;
                    pipe.close();
                }
                return msg;
            }
            catch (ZMQException e) {
                locked = true;
                return null;
            }
        }

        @Override
        public boolean send(ZMsg message)
        {
            if (locked) {
                return false;
            }
            return message.send(pipe);
        }

        @Override
        public boolean send(String word)
        {
            if (locked) {
                return false;
            }
            return pipe.send(word);
        }

        @Override
        public boolean send(String word, boolean more)
        {
            if (locked) {
                return false;
            }
            return pipe.send(word, more ? ZMQ.SNDMORE : 0);
        }

        @Override
        public boolean send(ZMsg msg, boolean destroy)
        {
            if (locked) {
                return false;
            }
            return msg.send(pipe, destroy);
        }

        @Override
        public Socket pipe()
        {
            return pipe;
        }

        @Override
        public void nova()
        {
            pipe.close();
        }
    }

    /**
     * Creates a selector and destroys it.
     * 创建一个selector和销毁它
     */
    // Contract for selector creation.
    // will be called in backstage side.
    public static interface SelectorCreator
    {
        /**
         * Creates and opens a selector.
         *
         * @return the opened selector.
         */
        Selector create() throws IOException;

        /**
         * Destroys the previously opened selector.
         * @param selector the selector to close
         */
        void destroy(Selector selector) throws IOException;
    }

    // very simple selector creator
    public static class VerySimpleSelectorCreator implements SelectorCreator
    {
        @Override
        public Selector create() throws IOException
        {
            return Selector.open();
        }

        @Override
        public void destroy(Selector selector) throws IOException
        {
            if (selector != null) {
                selector.close();
            }
        }
    }
}
