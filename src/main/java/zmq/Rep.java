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

public class Rep extends Router
{
    public static class RepSession extends Router.RouterSession
    {
        public RepSession(IOThread ioThread, boolean connect,
            SocketBase socket, final Options options,
            final Address addr)
        {
            super(ioThread, connect, socket, options, addr);
        }
    }
    //  If true, we are in process of sending the reply. If false we are
    //  in process of receiving a request.  如果为true,我们在处理发送回执,如果为false,则是处理获取一个请求
    private boolean sendingReply;

    //  If true, we are starting to receive a request. The beginning
    //  of the request is the backtrace stack.  如果为true,开始获取请求,请求的开始是回溯stack
    private boolean requestBegins;

    public Rep(Ctx parent, int tid, int sid)
    {
        super(parent, tid, sid);
        sendingReply = false;
        requestBegins = true;

        options.type = ZMQ.ZMQ_REP;
    }

    @Override
    protected boolean xsend(Msg msg)
    {
        //  If we are in the middle of receiving a request, we cannot send reply.   如果在获取请求中,不能发送回执
        if (!sendingReply) {
            throw new IllegalStateException("Cannot send another reply");
        }

        boolean more = msg.hasMore();

        //  Push message to the reply pipe.  发送消息到回执pipe
        boolean rc = super.xsend(msg);
        if (!rc) {
            return rc;
        }

        //  If the reply is complete flip the FSM back to request receiving state. 如果回执完成,返回到获取消息的状态
        if (!more) {
            sendingReply = false;
        }

        return true;
    }

    @Override
    protected Msg xrecv()
    {
        //  If we are in middle of sending a reply, we cannot receive next request.   如果在获取请求中,不能发送回执
        if (sendingReply) {
            throw new IllegalStateException("Cannot receive another request");
        }

        Msg msg = null;
        //  First thing to do when receiving a request is to copy all the labels
        //  to the reply pipe.    当获取一个请求时,首先负责所有的label到回执pipe
        if (requestBegins) {
            while (true) {
                msg = super.xrecv();
                if (msg == null) {
                    return null;
                }

                if (msg.hasMore()) {
                    //  Empty message part delimits the traceback stack.  空消息
                    boolean bottom = (msg.size() == 0);

                    //  Push it to the reply pipe.  push到回执pipe
                    boolean rc = super.xsend(msg);
                    assert (rc);
                    if (bottom) {
                        break;
                    }
                }
                else {
                    //  If the traceback stack is malformed, discard anything
                    //  already sent to pipe (we're at end of invalid message).
                    super.rollback();
                }
            }
            requestBegins = false;
        }

        //  Get next message part to return to the user.  获取下一部分的消息返回给用户
        msg = super.xrecv();
        if (msg == null) {
            return null;
        }

        //  If whole request is read, flip the FSM to reply-sending state.  如果所有的消息都read,返回到回执发送状态
        if (!msg.hasMore()) {
            sendingReply = true;
            requestBegins = true;
        }

        return msg;
    }

    @Override
    protected boolean xhasIn()
    {
        return !sendingReply && super.xhasIn();
    }

    @Override
    protected boolean xhasOut()
    {
        return sendingReply && super.xhasOut();
    }
}
