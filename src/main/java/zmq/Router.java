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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

//TODO: This class uses O(n) scheduling. Rewrite it to use O(1) algorithm.
public class Router extends SocketBase
{
    public static class RouterSession extends SessionBase
    {
        public RouterSession(IOThread ioThread, boolean connect,
            SocketBase socket, final Options options,
            final Address addr)
        {
            super(ioThread, connect, socket, options, addr);
        }
    }

    //  Fair queueing object for inbound pipes.  公平的queue对象为inbound pipe
    private final FQ fq;

    //  True iff there is a message held in the pre-fetch buffer. 如果一个消息持有预取的buffer则为true
    private boolean prefetched;

    //  If true, the receiver got the message part with  
    //  the peer's identity.  如果为true 接收则获取的消息有peer的标示
    private boolean identitySent;

    //  Holds the prefetched identity.
    private Msg prefetchedId;

    //  Holds the prefetched message.
    private Msg prefetchedMsg;

    //  If true, more incoming message parts are expected. 如果为true,更多的消息会被期待
    private boolean moreIn;

    class Outpipe
    {
        private Pipe pipe;
        private boolean active;

        public Outpipe(Pipe pipe, boolean active)
        {
            this.pipe = pipe;
            this.active = active;
        }
    };

    //  We keep a set of pipes that have not been identified yet. 保存部分尚未确认的pipe
    private final Set<Pipe> anonymousPipes;

    //  Outbound pipes indexed by the peer IDs.  
    private final Map<Blob, Outpipe> outpipes;

    //  The pipe we are currently writing to.  当前正写入的pipe
    private Pipe currentOut;

    //  If true, more outgoing message parts are expected.  如果true,更多发送的消息
    private boolean moreOut;

    //  Peer ID are generated. It's a simple increment and wrap-over
    //  algorithm. This value is the next ID to use (if not used already).  peer id 生成,简单的递增,这个值是下一个id使用
    private int nextPeerId;

    // If true, report EHOSTUNREACH to the caller instead of silently dropping
    // the message targeting an unknown peer. 如果true,
    private boolean mandatory;

    private boolean handover;

    public Router(Ctx parent, int tid, int sid)
    {
        super(parent, tid, sid);
        prefetched = false;
        identitySent = false;
        moreIn = false;
        currentOut = null;
        moreOut = false;
        nextPeerId = Utils.generateRandom();
        mandatory = false;
        handover = false;

        options.type = ZMQ.ZMQ_ROUTER;

        fq = new FQ();
        prefetchedId = new Msg();
        prefetchedMsg = new Msg();

        anonymousPipes = new HashSet<Pipe>();
        outpipes = new HashMap<Blob, Outpipe>();

        //  TODO: Uncomment the following line when ROUTER will become true ROUTER
        //  rather than generic router socket.
        //  If peer disconnect there's noone to send reply to anyway. We can drop
        //  all the outstanding requests from that peer.
        //  options.delayOnDisconnect = false;

        options.recvIdentity = true;
    }

    @Override
    public void xattachPipe(Pipe pipe, boolean icanhasall)
    {
        assert (pipe != null);

        boolean identityOk = identifyPeer(pipe);
        if (identityOk) {
            fq.attach(pipe);
        }
        else {
            anonymousPipes.add(pipe);
        }
    }

    @Override
    public boolean xsetsockopt(int option, Object optval)
    {
        if (option == ZMQ.ZMQ_ROUTER_MANDATORY) {
            mandatory = (Integer) optval == 1;
            return true;
        }
        if (option == ZMQ.ZMQ_ROUTER_HANDOVER) {
            handover = (Integer) optval == 1;
            return true;
        }
        return false;
    }

    @Override
    public void xpipeTerminated(Pipe pipe)
    {
        if (!anonymousPipes.remove(pipe)) {
            Outpipe old = outpipes.remove(pipe.getIdentity());
            assert (old != null);

            fq.terminated(pipe);
            if (pipe == currentOut) {
                currentOut = null;
            }
        }
    }

    @Override
    public void xreadActivated(Pipe pipe)
    {
        if (!anonymousPipes.contains(pipe)) {
            fq.activated(pipe);
        }
        else {
            boolean identityOk = identifyPeer(pipe);
            if (identityOk) {
                anonymousPipes.remove(pipe);
                fq.attach(pipe);
            }
        }
    }

    @Override
    public void xwriteActivated(Pipe pipe)
    {
        for (Map.Entry<Blob, Outpipe> it : outpipes.entrySet()) {
            if (it.getValue().pipe == pipe) {
                assert (!it.getValue().active);
                it.getValue().active = true;
                return;
            }
        }
        assert (false);
    }

    @Override
    protected boolean xsend(Msg msg)
    {
        //  If this is the first part of the message it's the ID of the
        //  peer to send the message to.  如果这是消息的第一部分,
        if (!moreOut) {
            assert (currentOut == null);

            //  If we have malformed message (prefix with no subsequent message)
            //  then just silently ignore it.
            //  TODO: The connections should be killed instead.
            if (msg.hasMore()) {
                moreOut = true;

                //  Find the pipe associated with the identity stored in the prefix.
                //  If there's no such pipe just silently ignore the message, unless
                //  mandatory is set.  找到pipe管理的标示存储在prefix,如果没有这样的pipe,就忽略掉这个消息
                Blob identity = Blob.createBlob(msg.data(), true);
                Outpipe op = outpipes.get(identity);

                if (op != null) {
                    currentOut = op.pipe;
                    if (!currentOut.checkWrite()) {
                        op.active = false;
                        currentOut = null;
                        if (mandatory) {
                            moreOut = false;
                            errno.set(ZError.EAGAIN);
                            return false;
                        }
                    }
                }
                else if (mandatory) {
                    moreOut = false;
                    errno.set(ZError.EHOSTUNREACH);
                    return false;
                }
            }

            return true;
        }

        //  Check whether this is the last part of the message.
        moreOut = msg.hasMore();

        //  Push the message into the pipe. If there's no out pipe, just drop it. push消息给pipe,如果没有out pipe,就直接drop掉
        if (currentOut != null) {
            boolean ok = currentOut.write(msg);
            if (!ok) {
                currentOut = null;
            }
            else if (!moreOut) {
                currentOut.flush();
                currentOut = null;
            }
        }

        return true;
    }

    @Override
    protected Msg xrecv()
    {
        Msg msg = null;
        if (prefetched) {
            if (!identitySent) {
                msg = prefetchedId;
                prefetchedId = null;
                identitySent = true;
            }
            else {
                msg = prefetchedMsg;
                prefetchedMsg = null;
                prefetched = false;
            }
            moreIn = msg.hasMore();
            return msg;
        }

        ValueReference<Pipe> pipe = new ValueReference<Pipe>();
        msg = fq.recvPipe(errno, pipe);

        //  It's possible that we receive peer's identity. That happens
        //  after reconnection. The current implementation assumes that
        //  the peer always uses the same identity.
        //  TODO: handle the situation when the peer changes its identity.
        while (msg != null && msg.isIdentity()) {
            msg = fq.recvPipe(errno, pipe);
        }

        if (msg == null) {
            return null;
        }

        assert (pipe.get() != null);

        //  If we are in the middle of reading a message, just return the next part.  如果在读取消息的中间,就返回下一部分
        if (moreIn) {
            moreIn = msg.hasMore();
        }
        else {
            //  We are at the beginning of a message.
            //  Keep the message part we have in the prefetch buffer
            //  and return the ID of the peer instead.
            prefetchedMsg = msg;
            prefetched = true;

            Blob identity = pipe.get().getIdentity();
            msg = new Msg(identity.data());
            msg.setFlags(Msg.MORE);
            identitySent = true;
        }

        return msg;
    }

    //  Rollback any message parts that were sent but not yet flushed. 回滚任何消息已经send但是没有flush的
    protected void rollback()
    {
        if (currentOut != null) {
            currentOut.rollback();
            currentOut = null;
            moreOut = false;
        }
    }

    @Override
    protected boolean xhasIn()
    {
        //  If we are in the middle of reading the messages, there are
        //  definitely more parts available.  如果我们在读取消息的终结,就存在更多的消息
        if (moreIn) {
            return true;
        }

        //  We may already have a message pre-fetched.  已经存在预取的数据
        if (prefetched) {
            return true;
        }

        //  Try to read the next message.
        //  The message, if read, is kept in the pre-fetch buffer.   读取下一个消息,如果读取,会保存在预取的缓存中
        ValueReference<Pipe> pipe = new ValueReference<Pipe>();
        prefetchedMsg = fq.recvPipe(errno, pipe);

        //  It's possible that we receive peer's identity. That happens
        //  after reconnection. The current implementation assumes that
        //  the peer always uses the same identity.
        //  TODO: handle the situation when the peer changes its identity.
        /**
         * 有可能我们读取到peer的标记,这发生在重新连接后
         */
        while (prefetchedMsg != null && prefetchedMsg.isIdentity()) {
            prefetchedMsg = fq.recvPipe(errno, pipe);
        }

        if (prefetchedMsg == null) {
            return false;
        }

        assert (pipe.get() != null);

        Blob identity = pipe.get().getIdentity();
        prefetchedId = new Msg(identity.data());
        prefetchedId.setFlags(Msg.MORE);

        prefetched = true;
        identitySent = false;

        return true;
    }

    @Override
    protected boolean xhasOut()
    {
        //  In theory, ROUTER socket is always ready for writing. Whether actual
        //  attempt to write succeeds depends on which pipe the message is going
        //  to be routed to.   理论上ROUTER socket总是准备写入的,无论时间试图写入成功依赖于那些pipe的消息将要routed
        return true;
    }

    private boolean identifyPeer(Pipe pipe)
    {
        Blob identity;

        Msg msg = pipe.read();
        if (msg == null) {
            return false;
        }

        if (msg.size() == 0) {
            //  Fall back on the auto-generation     回退到自动生成
            ByteBuffer buf = ByteBuffer.allocate(5);
            buf.put((byte) 0);
            buf.putInt(nextPeerId++);
            identity = Blob.createBlob(buf.array(), false);
        }
        else {
            identity = Blob.createBlob(msg.data(), true);

            if (outpipes.containsKey(identity)) {
                if (!handover) {
                    return false;
                }
                //  We will allow the new connection to take over this
                //  identity. Temporarily assign a new identity to the
                //  existing pipe so we can terminate it asynchronously.
                /**
                 * 我们会允许新连接去接管这个标记,暂时指定一个新标记给已经存在的pipe,这样我们能异步的terminate它
                 */
                ByteBuffer buf = ByteBuffer.allocate(5);
                buf.put((byte) 0);
                buf.putInt(nextPeerId++);
                Blob newIdentity = Blob.createBlob(buf.array(), false);

                //  Remove the existing identity entry to allow the new
                //  connection to take the identity.  移除已经存在的标记,从而允许新连接获取该标记
                Outpipe existingOutpipe = outpipes.remove(identity);
                existingOutpipe.pipe.setIdentity(newIdentity);

                outpipes.put(newIdentity, existingOutpipe);

                existingOutpipe.pipe.terminate(true);
            }
        }

        pipe.setIdentity(identity);
        //  Add the record into output pipes lookup table
        Outpipe outpipe = new Outpipe(pipe, true);
        outpipes.put(identity, outpipe);

        return true;
    }
}
