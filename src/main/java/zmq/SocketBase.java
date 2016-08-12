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

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public abstract class SocketBase extends Own
    implements IPollEvents, Pipe.IPipeEvents
{
    //  Map of open endpoints.
    /**
     * Map of open endpoints.
     */
    private final Map<String, Own> endpoints;

    //  Map of open inproc endpoints.
    /**
     * Map of open inproc endpoints.
     */
    private final Map<String, Pipe> inprocs;

    //  Used to check whether the object is a socket.
    /**
     * 检测该对象是否是一个socket
     */
    private int tag;

    //  If true, associated context was already terminated.
    /**
     * 如果true 关联的上下问已经关闭
     */
    private boolean ctxTerminated;

    //  If true, object should have been already destroyed. However,
    //  destruction is delayed while we unwind the stack to the point
    //  where it doesn't intersect the object being destroyed.
    /**
     * 如果是true,对象应该已经被销毁了,
     * 但是析构函数延迟调用,
     */
    private boolean destroyed;

    //  Socket's mailbox object.  socket的邮箱
    private final Mailbox mailbox;

    //  List of attached pipes. 管理的pipe
    private final List<Pipe> pipes;

    //  Reaper's poller and handle of this socket within it.  reaper的poller
    private Poller poller;
    private SelectableChannel handle;

    //  Timestamp of when commands were processed the last time.   最近处理的命令的时间
    private long lastTsc;

    //  Number of messages received since last command processing.  最近命令处理中获取的消息数量
    private int ticks;

    //  True if the last message received had MORE flag set.   如果最近获取的消息有more标记,则为true
    private boolean rcvmore;

    // Monitor socket 监控socket
    private SocketBase monitorSocket;

    // Bitmask of events being monitored   监控事件
    private int monitorEvents;

    protected ValueReference<Integer> errno;

    protected SocketBase(Ctx parent, int tid, int sid)
    {
        super(parent, tid);
        tag = 0xbaddecaf;
        ctxTerminated = false;
        destroyed = false;
        lastTsc = 0;
        ticks = 0;
        rcvmore = false;
        monitorSocket = null;
        monitorEvents = 0;

        options.socketId = sid;
        options.linger = parent.get(ZMQ.ZMQ_BLOCKY) != 0 ? -1 : 0;

        endpoints = new MultiMap<String, Own>();
        inprocs = new MultiMap<String, Pipe>();
        pipes = new ArrayList<Pipe>();

        mailbox = new Mailbox("socket-" + sid);

        errno = new ValueReference<Integer>(0);
    }

    //  Concrete algorithms for the x- methods are to be defined by
    //  individual socket types.
    protected abstract void xattachPipe(Pipe pipe, boolean icanhasall);
    protected abstract void xpipeTerminated(Pipe pipe);

    //  Returns false if object is not a socket.  如果该对象不是一个socket返回false
    public boolean checkTag()
    {
        return tag == 0xbaddecaf;
    }

    //  Create a socket of a specified type.
    /**
     * 根据给定类型创建socket
     * 
     * @param type
     * @param parent
     * @param tid
     * @param sid
     * @return
     *
     * @author {yourname} 2016年8月10日 下午4:13:20
     */
    public static SocketBase create(int type, Ctx parent, int tid, int sid)
    {
        SocketBase s = null;
        switch (type) {
        case ZMQ.ZMQ_PAIR:
            s = new Pair(parent, tid, sid);
            break;
        case ZMQ.ZMQ_PUB:
            s = new Pub(parent, tid, sid);
            break;
        case ZMQ.ZMQ_SUB:
            s = new Sub(parent, tid, sid);
            break;
        case ZMQ.ZMQ_REQ:
            s = new Req(parent, tid, sid);
            break;
        case ZMQ.ZMQ_REP:
            s = new Rep(parent, tid, sid);
            break;
        case ZMQ.ZMQ_DEALER:
            s = new Dealer(parent, tid, sid);
            break;
        case ZMQ.ZMQ_ROUTER:
            s = new Router(parent, tid, sid);
            break;
        case ZMQ.ZMQ_PULL:
            s = new Pull(parent, tid, sid);
            break;
        case ZMQ.ZMQ_PUSH:
            s = new Push(parent, tid, sid);
            break;

        case ZMQ.ZMQ_XPUB:
            s = new XPub(parent, tid, sid);
            break;

        case ZMQ.ZMQ_XSUB:
            s = new XSub(parent, tid, sid);
            break;

        default:
            throw new IllegalArgumentException("type=" + type);
        }
        return s;
    }

    public void destroy()
    {
        try {
            mailbox.close();
        }
        catch (IOException ignore) {
        }

        stopMonitor();
        assert (destroyed);
    }

    //  Returns the mailbox associated with this socket. 
    public Mailbox getMailbox()
    {
        return mailbox;
    }

    //  Interrupt blocking call if the socket is stuck in one.
    //  This function can be called from a different thread! 
    /*
     * 终结block调用如果socket,
     */
    public void stop()
    {
        //  Called by ctx when it is terminated (zmq_term).
        //  'stop' command is sent from the threads that called zmq_term to
        //  the thread owning the socket. This way, blocking call in the
        //  owner thread can be interrupted.
        /**
         * 有context调用当他terminated时,stop命令从调用zmq_term的线程到拥有该socket的线程
         * 这样block的调用在线程拥有者可以被中断
         */
        sendStop();
    }

    //  Check whether transport protocol, as specified in connect or
    //  bind, is available and compatible with the socket type.
    /**
     * 检查传输协议
     * 
     * @param protocol
     *
     * @author {yourname} 2016年8月10日 下午5:02:04
     */
    private void checkProtocol(String protocol)
    {
        //  First check out whether the protcol is something we are aware of. 
        if (!protocol.equals("inproc") && !protocol.equals("ipc") && !protocol.equals("tcp") /*&&
              !protocol.equals("pgm") && !protocol.equals("epgm")*/) {
            throw new UnsupportedOperationException(protocol);
        }

        //  Check whether socket type and transport protocol match.
        //  Specifically, multicast protocols can't be combined with
        //  bi-directional messaging patterns (socket types).
        if ((protocol.equals("pgm") || protocol.equals("epgm")) &&
              options.type != ZMQ.ZMQ_PUB && options.type != ZMQ.ZMQ_SUB &&
              options.type != ZMQ.ZMQ_XPUB && options.type != ZMQ.ZMQ_XSUB) {
            throw new UnsupportedOperationException(protocol + ",type=" + options.type);
        }

        //  Protocol is available.
    }

    //  Register the pipe with this socket.
    //注册这个pipe到socket
    private void attachPipe(Pipe pipe)
    {
        attachPipe(pipe, false);
    }

    private void attachPipe(Pipe pipe, boolean icanhasall)
    {
        //  First, register the pipe so that we can terminate it later on.
        // 首先注册pipe这样之后就可以直接terminate
        pipe.setEventSink(this);
        pipes.add(pipe);

        //  Let the derived socket type know about new pipe.  让衍生的socket类型知道新的pipe
        xattachPipe(pipe, icanhasall);

        //  If the socket is already being closed, ask any new pipes to terminate
        //  straight away.  如果socket已经关闭,请求任何新pipe直接terminate
        if (isTerminating()) {
            registerTermAcks(1);
            pipe.terminate(false);
        }
    }

    public void setSocketOpt(int option, Object optval)
    {
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        //  First, check whether specific socket type overloads the option. 首先检查特定的socket类型是否重载该选项
        if (xsetsockopt(option, optval)) {
            return;
        }

        //  If the socket type doesn't support the option, pass it to
        //  the generic option parser.  如果该socket类型不支持该选项,交给通用的选项处理
        options.setSocketOpt(option, optval);
    }

    public int getSocketOpt(int option)
    {
        if (option != ZMQ.ZMQ_EVENTS && ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        // fast track to avoid boxing
        if (option == ZMQ.ZMQ_RCVMORE) {
            return rcvmore ? 1 : 0;
        }
        if (option == ZMQ.ZMQ_EVENTS) {
            boolean rc = processCommands(0, false);
            if (!rc && errno.get() == ZError.ETERM) {
                return -1;
            }
            assert (rc);
            int val = 0;
            if (hasOut()) {
                val |= ZMQ.ZMQ_POLLOUT;
            }
            if (hasIn()) {
                val |= ZMQ.ZMQ_POLLIN;
            }
            return val;
        }

        return (Integer) getsockoptx(option);
    }

    public Object getsockoptx(int option)
    {
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        if (option == ZMQ.ZMQ_RCVMORE) {
            return rcvmore ? 1 : 0;
        }

        if (option == ZMQ.ZMQ_FD) {
            return mailbox.getFd();
        }

        if (option == ZMQ.ZMQ_EVENTS) {
            boolean rc = processCommands(0, false);
            if (!rc && errno.get() == ZError.ETERM) {
                return -1;
            }
            assert (rc);
            int val = 0;
            if (hasOut()) {
                val |= ZMQ.ZMQ_POLLOUT;
            }
            if (hasIn()) {
                val |= ZMQ.ZMQ_POLLIN;
            }
            return val;
        }
        //  If the socket type doesn't support the option, pass it to
        //  the generic option parser.
        return options.getsockopt(option);
    }

    public boolean bind(final String addr)
    {
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        //  Process pending commands, if any.
        boolean brc = processCommands(0, false);
        if (!brc) {
            return false;
        }

        SimpleURI uri = SimpleURI.create(addr);
        String protocol = uri.getProtocol();
        String address = uri.getAddress();

        checkProtocol(protocol);

        if (protocol.equals("inproc")) {
            Ctx.Endpoint endpoint = new Ctx.Endpoint(this, options);
            boolean rc = registerEndpoint(addr, endpoint);
            if (rc) {
                // Save last endpoint URI
                options.lastEndpoint = addr;
            }
            else {
                errno.set(ZError.EADDRINUSE);
            }
            return rc;
        }
        if (protocol.equals("pgm") || protocol.equals("epgm")) {
            //  For convenience's sake, bind can be used interchageable with
            //  connect for PGM and EPGM transports.
            return connect(addr);
        }

        //  Remaining trasnports require to be run in an I/O thread, so at this
        //  point we'll choose one.
        IOThread ioThread = chooseIoThread(options.affinity);
        if (ioThread == null) {
            throw new IllegalStateException("EMTHREAD");
        }

        if (protocol.equals("tcp")) {
            TcpListener listener = new TcpListener(ioThread, this, options);
            int rc = listener.setAddress(address);
            if (rc != 0) {
                listener.destroy();
                eventBindFailed(address, rc);
                errno.set(rc);
                return false;
            }

            // Save last endpoint URI
            options.lastEndpoint = listener.getAddress();

            addEndpoint(options.lastEndpoint, listener);
            return true;
        }

        if (protocol.equals("ipc")) {
            IpcListener listener = new IpcListener(ioThread, this, options);
            int rc = listener.setAddress(address);
            if (rc != 0) {
                listener.destroy();
                eventBindFailed(address, rc);
                errno.set(rc);
                return false;
            }

            // Save last endpoint URI
            options.lastEndpoint = listener.getAddress();

            addEndpoint(addr, listener);
            return true;
        }

        throw new IllegalArgumentException(addr);
    }

    public boolean connect(String addr)
    {
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        //  Process pending commands, if any.
        boolean brc = processCommands(0, false);
        if (!brc) {
            return false;
        }

        SimpleURI uri = SimpleURI.create(addr);
        String protocol = uri.getProtocol();
        String address = uri.getAddress();

        checkProtocol(protocol);

        if (protocol.equals("inproc")) {
            //  TODO: inproc connect is specific with respect to creating pipes
            //  as there's no 'reconnect' functionality implemented. Once that
            //  is in place we should follow generic pipe creation algorithm.

            //  Find the peer endpoint.
            Ctx.Endpoint peer = findEndpoint(addr);
            if (peer.socket == null) {
                return false;
            }
            // The total HWM for an inproc connection should be the sum of
            // the binder's HWM and the connector's HWM.
            int sndhwm = 0;
            if (options.sendHwm != 0 && peer.options.recvHwm != 0) {
                sndhwm = options.sendHwm + peer.options.recvHwm;
            }
            int rcvhwm = 0;
            if (options.recvHwm != 0 && peer.options.sendHwm != 0) {
                rcvhwm = options.recvHwm + peer.options.sendHwm;
            }

            //  Create a bi-directional pipe to connect the peers.
            ZObject[] parents = {this, peer.socket};
            Pipe[] pipes = {null, null};
            int[] hwms = {sndhwm, rcvhwm};
            boolean[] delays = {options.delayOnDisconnect, options.delayOnClose};
            Pipe.pipepair(parents, pipes, hwms, delays);

            //  Attach local end of the pipe to this socket object.
            attachPipe(pipes[0]);

            //  If required, send the identity of the peer to the local socket.
            if (peer.options.recvIdentity) {
                Msg id = new Msg(options.identitySize);
                id.put(options.identity, 0 , options.identitySize);
                id.setFlags(Msg.IDENTITY);
                boolean written = pipes[0].write(id);
                assert (written);
                pipes[0].flush();
            }

            //  If required, send the identity of the local socket to the peer.
            if (options.recvIdentity) {
                Msg id = new Msg(peer.options.identitySize);
                id.put(peer.options.identity, 0 , peer.options.identitySize);
                id.setFlags(Msg.IDENTITY);
                boolean written = pipes[1].write(id);
                assert (written);
                pipes[1].flush();
            }

            //  Attach remote end of the pipe to the peer socket. Note that peer's
            //  seqnum was incremented in findEndpoint function. We don't need it
            //  increased here.
            sendBind(peer.socket, pipes[1], false);

            // Save last endpoint URI
            options.lastEndpoint = addr;

            // remember inproc connections for disconnect
            inprocs.put(addr, pipes[0]);

            return true;
        }

        //  Choose the I/O thread to run the session in.
        IOThread ioThread = chooseIoThread(options.affinity);
        if (ioThread == null) {
            throw new IllegalStateException("Empty IO Thread");
        }
        boolean ipv4only = options.ipv4only != 0;
        Address paddr = new Address(protocol, address, ipv4only);

        //  Resolve address (if needed by the protocol)
        paddr.resolve();

        //  Create session.
        SessionBase session = SessionBase.create(ioThread, true, this,
            options, paddr);
        assert (session != null);

        //  PGM does not support subscription forwarding; ask for all data to be
        //  sent to this pipe.
        boolean icanhasall = false;
        if (protocol.equals("pgm") || protocol.equals("epgm")) {
            icanhasall = true;
        }

        if (options.delayAttachOnConnect != 1 || icanhasall) {
            //  Create a bi-directional pipe.
            ZObject[] parents = {this, session};
            Pipe[] pipes = {null, null};
            int[] hwms = {options.sendHwm, options.recvHwm};
            boolean[] delays = {options.delayOnDisconnect, options.delayOnClose};
            Pipe.pipepair(parents, pipes, hwms, delays);

            //  Attach local end of the pipe to the socket object.   attach本地的pipe到socket
            attachPipe(pipes[0], icanhasall);

            //  Attach remote end of the pipe to the session object later on.  attach远程pipe到session
            session.attachPipe(pipes[1]);
        }

        // Save last endpoint URI
        options.lastEndpoint = paddr.toString();

        addEndpoint(addr, session);
        return true;
    }

    //  Creates new endpoint ID and adds the endpoint to the map.
    /**
     * 创建一个新的end point id和添加这个endpoint到map
     * 
     * @param addr
     * @param endpoint
     *
     * @author {yourname} 2016年8月12日 上午10:36:58
     */
    private void addEndpoint(String addr, Own endpoint)
    {
        //  Activate the session. Make it a child of this socket. 激活这个session,这个socket 创建该socket的子类
        launchChild(endpoint);
        endpoints.put(addr, endpoint);
    }

    public boolean termEndpoint(String addr)
    {
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        //  Check whether endpoint address passed to the function is valid.  检测该endpoint 地址是否合法
        if (addr == null) {
            throw new IllegalArgumentException();
        }

        //  Process pending commands, if any, since there could be pending unprocessed processOwn()'s
        //  (from launchChild() for example) we're asked to terminate now.  
        /**
         * 处理pending命令,
         */
        boolean rc = processCommands(0, false);
        if (!rc) {
            return false;
        }

        SimpleURI uri = SimpleURI.create(addr);
        String protocol = uri.getProtocol();

        // Disconnect an inproc socket  断开inproc socket 
        if (protocol.equals("inproc")) {
            if (!inprocs.containsKey(addr)) {
                return false;
            }

            Iterator<Entry<String, Pipe>> it = inprocs.entrySet().iterator();
            while (it.hasNext()) {
                it.next().getValue().terminate(true);
                it.remove();
            }
            return true;
        }

        if (!endpoints.containsKey(addr)) {
            return false;
        }
        //  Find the endpoints range (if any) corresponding to the addr_ string.   
        Iterator<Entry<String, Own>> it = endpoints.entrySet().iterator();

        while (it.hasNext()) {
            Entry<String, Own> e = it.next();
            if (!e.getKey().equals(addr)) {
                continue;
            }
            termChild(e.getValue());
            it.remove();
        }
        return true;

    }

    public boolean send(Msg msg, int flags)
    {
        if (ctxTerminated) {
            errno.set(ZError.ETERM);
            return false;
        }

        //  Check whether message passed to the function is valid.  检测消息是否合法
        if (msg == null) {
            throw new IllegalArgumentException();
        }

        //  Process pending commands, if any.   处理pending命令
        boolean brc = processCommands(0, true);
        if (!brc) {
            return false;
        }

        //  Clear any user-visible flags that are set on the message.  清除任何用户可见的flag,设置在消息上的
        msg.resetFlags(Msg.MORE);

        //  At this point we impose the flags on the message.    使用标记
        if ((flags & ZMQ.ZMQ_SNDMORE) > 0) {
            msg.setFlags(Msg.MORE);
        }

        //  Try to send the message.   尝试发送消息
        boolean rc = xsend(msg);

        if (rc) {
            return true;
        }

        if (errno.get() != ZError.EAGAIN) {
            return false;
        }

        //  In case of non-blocking send we'll simply propagate
        //  the error - including EAGAIN - up the stack.     
        if ((flags & ZMQ.ZMQ_DONTWAIT) > 0 || options.sendTimeout == 0) {
            return false;
        }

        //  Compute the time when the timeout should occur.
        //  If the timeout is infite, don't care. 计算超时时间,
        int timeout = options.sendTimeout;
        long end = timeout < 0 ? 0 : (Clock.nowMS() + timeout);

        //  Oops, we couldn't send the message. Wait for the next
        //  command, process it and try to send the message again.
        //  If timeout is reached in the meantime, return EAGAIN.
        while (true) {
            if (!processCommands(timeout, false)) {
                return false;
            }

            rc = xsend(msg);
            if (rc) {
                break;
            }

            if (errno.get() != ZError.EAGAIN) {
                return false;
            }

            if (timeout > 0) {
                timeout = (int) (end - Clock.nowMS());
                if (timeout <= 0) {
                    errno.set(ZError.EAGAIN);
                    return false;
                }
            }
        }
        return true;
    }

    public Msg recv(int flags)
    {
        if (ctxTerminated) {
            errno.set(ZError.ETERM);
            return null;
        }

        //  Once every inbound_poll_rate messages check for signals and process
        //  incoming commands. This happens only if we are not polling altogether
        //  because there are messages available all the time. If poll occurs,
        //  ticks is set to zero and thus we avoid this code.
        //
        //  Note that 'recv' uses different command throttling algorithm (the one
        //  described above) from the one used by 'send'. This is because counting
        //  ticks is more efficient than doing RDTSC all the time.
        if (++ticks == Config.INBOUND_POLL_RATE.getValue()) {
            if (!processCommands(0, false)) {
                return null;
            }
            ticks = 0;
        }

        //  Get the message.
        Msg msg = xrecv();
        if (msg == null && errno.get() != ZError.EAGAIN) {
            return null;
        }

        //  If we have the message, return immediately.
        if (msg != null) {
            extractFlags(msg);
            return msg;
        }

        //  If the message cannot be fetched immediately, there are two scenarios.
        //  For non-blocking recv, commands are processed in case there's an
        //  activate_reader command already waiting in a command pipe.
        //  If it's not, return EAGAIN.  如果消息无法直接获取,对于非block读取,处理命令,以免 activate_reader 命令已经在命令pipe里面等待
        if ((flags & ZMQ.ZMQ_DONTWAIT) > 0 || options.recvTimeout == 0) {
            if (!processCommands(0, false)) {
                return null;
            }
            ticks = 0;

            msg = xrecv();
            if (msg == null) {
                return null;
            }
            extractFlags(msg);
            return msg;
        }

        //  Compute the time when the timeout should occur.
        //  If the timeout is infite, don't care.  计算超时
        int timeout = options.recvTimeout;
        long end = timeout < 0 ? 0 : (Clock.nowMS() + timeout);

        //  In blocking scenario, commands are processed over and over again until
        //  we are able to fetch a message.   在block模式下,命令会一遍一遍的处理,直到我们能获取一下消息
        boolean block = (ticks != 0);
        while (true) {
            if (!processCommands(block ? timeout : 0, false)) {
                return null;
            }
            msg = xrecv();

            if (msg != null) {
                ticks = 0;
                break;
            }

            if (errno.get() != ZError.EAGAIN) {
                return null;
            }

            block = true;
            if (timeout > 0) {
                timeout = (int) (end - Clock.nowMS());
                if (timeout <= 0) {
                    errno.set(ZError.EAGAIN);
                    return null;
                }
            }
        }

        extractFlags(msg);
        return msg;

    }

    public void close()
    {
        //  Mark the socket as dead  标记socket已经dead
        tag = 0xdeadbeef;

        //  Transfer the ownership of the socket from this application thread
        //  to the reaper thread which will take care of the rest of shutdown
        //  process.   转换该socket的所有权从该线程给reaper线程,会负责之后的关闭
        sendReap(this);
    }

    //  These functions are used by the polling mechanism to determine
    //  which events are to be reported from this socket.  该方法被使用被polling 机器去决定哪些事件从socket中report
    boolean hasIn()
    {
        return xhasIn();
    }

    boolean hasOut()
    {
        return xhasOut();
    }

    //  Using this function reaper thread ask the socket to register with
    //  its poller.    reaper线程要求socket去注册他的poller
    public void startReaping(Poller poller)
    {
        //  Plug the socket to the reaper thread.  plug该socket到reaper线程
        this.poller = poller;
        handle = mailbox.getFd();
        this.poller.addHandle(handle, this);
        this.poller.setPollIn(handle);

        //  Initialise the termination and check whether it can be deallocated
        //  immediately.   初始化termination和检查是否能立刻释放
        terminate();
        checkDestroy();
    }

    //  Processes commands sent to this socket (if any). If timeout is -1,
    //  returns only after at least one command was processed.
    //  If throttle argument is true, commands are processed at most once
    //  in a predefined time period.
    /**
     * 
     * 处理命令发送给socket,如果timeout为-1,只有在至少有一个方法被处理才返回
     * 如果throttle为true,命令在预定的事件段内至少处理一次
     * 
     * @param timeout
     * @param throttle
     * @return
     *
     * @author {yourname} 2016年8月12日 下午2:52:49
     */
    private boolean processCommands(int timeout, boolean throttle)
    {
        Command cmd;
        if (timeout != 0) {
            //  If we are asked to wait, simply ask mailbox to wait.  如果需要等待,就要求邮箱等待
            cmd = mailbox.recv(timeout);
        }
        else {
            //  If we are asked not to wait, check whether we haven't processed
            //  commands recently, so that we can throttle the new commands.

            //  Get the CPU's tick counter. If 0, the counter is not available.
            
            // 如果不需要等待,检查最近未处理的命令,这样就可以限制新命令,获取cpu的tick计数器,如果为0,计数器不可用
            long tsc = 0; // save cpu Clock.rdtsc ();

            //  Optimised version of command processing - it doesn't have to check
            //  for incoming commands each time. It does so only if certain time
            //  elapsed since last command processing. Command delay varies
            //  depending on CPU speed: It's ~1ms on 3GHz CPU, ~2ms on 1.5GHz CPU
            //  etc. The optimisation makes sense only on platforms where getting
            //  a timestamp is a very cheap operation (tens of nanoseconds).
            /**
             * 命令处理的优化版本,不用每次都检查进来的命令,只用从上一次处理命令后超过给定时间才会处理
             * 命令延迟的变化依赖于cpu的速度,1ms在3GHz cpu,这种优化只用在平台获取一个时间戳很廉价的操作上.
             */
            if (tsc != 0 && throttle) {
                //  Check whether TSC haven't jumped backwards (in case of migration
                //  between CPU cores) and whether certain time have elapsed since
                //  last command processing. If it didn't do nothing. 检查tsc没有向后跳,同时释放过去了确定的时间
                if (tsc >= lastTsc && tsc - lastTsc <= Config.MAX_COMMAND_DELAY.getValue()) {
                    return true;
                }
                lastTsc = tsc;
            }

            //  Check whether there are any commands pending for this thread. 检查是否有命令
            cmd = mailbox.recv(0);
        }

        //  Process all the commands available at the moment.  处理所有命令
        while (true) {
            if (cmd == null) {
                break;
            }

            cmd.destination().processCommand(cmd);
            cmd = mailbox.recv(0);
        }
        if (ctxTerminated) {
            errno.set(ZError.ETERM); // Do not raise exception at the blocked operation
            return false;
        }

        return true;
    }

    @Override
    protected void processStop()
    {
        //  Here, someone have called zmq_term while the socket was still alive.
        //  We'll remember the fact so that any blocking call is interrupted and any
        //  further attempt to use the socket will return ETERM. The user is still
        //  responsible for calling zmq_close on the socket though!
        
        //当socket还在运行时,被调用zmq_term记录当前信息,这样所有block命令会被中断,
        //任何试图使用该socket都会返回ETERM,用户任然需要调用zmq_close
        stopMonitor();
        ctxTerminated = true;

    }

    @Override
    protected void processBind(Pipe pipe)
    {
        attachPipe(pipe);
    }

    @Override
    protected void processTerm(int linger)
    {
        //  Unregister all inproc endpoints associated with this socket.
        //  Doing this we make sure that no new pipes from other sockets (inproc)
        //  will be initiated.
        //取消注册在这个socket上的所有 inproc endpoint,这可以确保没有新的pipe从其他socket被初始化
        unregisterEndpoints(this);

        //  Ask all attached pipes to terminate.  请求所有关联的pipe注销
        for (int i = 0; i != pipes.size(); ++i) {
            pipes.get(i).terminate(false);
        }
        registerTermAcks(pipes.size());

        //  Continue the termination process immediately.  立刻继续处理termination
        super.processTerm(linger);
    }

    //  Delay actual destruction of the socket.
    @Override
    protected void processDestroy()
    {
        destroyed = true;
    }

    //  The default implementation assumes there are no specific socket
    //  options for the particular socket type. If not so, overload this
    //  method.
    protected boolean xsetsockopt(int option, Object optval)
    {
        return false;
    }

    protected boolean xhasOut()
    {
        return false;
    }

    protected boolean xsend(Msg msg)
    {
        throw new UnsupportedOperationException("Must Override");
    }

    protected boolean xhasIn()
    {
        return false;
    }

    protected Msg xrecv()
    {
        throw new UnsupportedOperationException("Must Override");
    }

    protected void xreadActivated(Pipe pipe)
    {
        throw new UnsupportedOperationException("Must Override");
    }

    protected void xwriteActivated(Pipe pipe)
    {
        throw new UnsupportedOperationException("Must Override");
    }

    protected void xhiccuped(Pipe pipe)
    {
        throw new UnsupportedOperationException("Must override");
    }

    @Override
    public void inEvent()
    {
        //  This function is invoked only once the socket is running in the context
        //  of the reaper thread. Process any commands from other threads/sockets
        //  that may be available at the moment. Ultimately, the socket will
        //  be destroyed.  
        /**
         * 该方法只会调用一次当该socket允许在reaper线程上下文时,处理其他的线程/socket
         */
        try {
            processCommands(0, false);
        }
        catch (ZError.CtxTerminatedException e) {
        }

        checkDestroy();
    }

    @Override
    public void outEvent()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void connectEvent()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void acceptEvent()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void timerEvent(int id)
    {
        throw new UnsupportedOperationException();
    }

    //  To be called after processing commands or invoking any command
    //  handlers explicitly. If required, it will deallocate the socket.
    /**
     * 在处理命令后调用或明确的调用命令之后
     * 
     *
     * @author {yourname} 2016年8月12日 下午3:45:14
     */
    private void checkDestroy()
    {
        //  If the object was already marked as destroyed, finish the deallocation.  如果对象已经被标记为销毁,
        if (destroyed) {
            //  Remove the socket from the reaper's poller.  清除socket从reaper的poller
            poller.removeHandle(handle);
            //  Remove the socket from the context.  移除socket从上下文
            destroySocket(this);

            //  Notify the reaper about the fact.  通知reaper
            sendReaped();

            //  Deallocate.
            super.processDestroy();
        }
    }

    @Override
    public void readActivated(Pipe pipe)
    {
        xreadActivated(pipe);
    }

    @Override
    public void writeActivated(Pipe pipe)
    {
        xwriteActivated(pipe);
    }

    @Override
    public void hiccuped(Pipe pipe)
    {
        if (options.delayAttachOnConnect == 1) {
            pipe.terminate(false);
        }
        else {
            // Notify derived sockets of the hiccup  通知继承类hiccup
            xhiccuped(pipe);
        }
    }

    @Override
    public void pipeTerminated(Pipe pipe)
    {
        //  Notify the specific socket type about the pipe termination.   通知子类pipe termination
        xpipeTerminated(pipe);

        // Remove pipe from inproc pipes   移除pipe从pipes
        Iterator<Entry<String, Pipe>> it = inprocs.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() == pipe) {
                it.remove();
                break;
            }
        }

        //  Remove the pipe from the list of attached pipes and confirm its
        //  termination if we are already shutting down.     移除pipe从管理的pipe list,确认是否termination
        pipes.remove(pipe);
        if (isTerminating()) {
            unregisterTermAck();
        }
    }

    //  Moves the flags from the message to local variables,
    //  to be later retrieved by getSocketOpt.  
    /**
     * 将flag赋给本地变量
     * 
     * @param msg
     *
     * @author {yourname} 2016年8月12日 下午3:51:50
     */
    private void extractFlags(Msg msg)
    {
        //  Test whether IDENTITY flag is valid for this socket type.  检测IDENTITY标签是否可用
        if ((msg.flags() & Msg.IDENTITY) > 0) {
            assert (options.recvIdentity);
        }

        //  Remove MORE flag.  移除more标签
        rcvmore = msg.hasMore();
    }

    public boolean monitor(final String addr, int events)
    {
        boolean rc;
        if (ctxTerminated) {
            throw new ZError.CtxTerminatedException();
        }

        // Support deregistering monitoring endpoints as well
        if (addr == null) {
            stopMonitor();
            return true;
        }

        SimpleURI uri = SimpleURI.create(addr);
        String protocol = uri.getProtocol();

        checkProtocol(protocol);

        // Event notification only supported over inproc://  事件通知只支持inproc
        if (!protocol.equals("inproc")) {
            stopMonitor();
            throw new IllegalArgumentException("inproc socket required");
        }

        // Register events to monitor  注册事件到监听器
        monitorEvents = events;

        monitorSocket = getCtx().createSocket(ZMQ.ZMQ_PAIR);
        if (monitorSocket == null) {
            return false;
        }

        // Never block context termination on pending event messages  永远不block上下文termination在pending 事件消息时
        int linger = 0;
        try {
            monitorSocket.setSocketOpt(ZMQ.ZMQ_LINGER, linger);
        }
        catch (IllegalArgumentException e) {
            stopMonitor();
            throw e;
        }

        // Spawn the monitor socket endpoint
        rc = monitorSocket.bind(addr);
        if (!rc) {
            stopMonitor();
        }
        return rc;
    }

    public void eventConnected(String addr, SelectableChannel ch)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_CONNECTED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_CONNECTED, addr, ch));
    }

    public void eventConnectDelayed(String addr, int errno)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_CONNECT_DELAYED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_CONNECT_DELAYED, addr, errno));
    }

    public void eventConnectRetried(String addr, int interval)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_CONNECT_RETRIED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_CONNECT_RETRIED, addr, interval));
    }

    public void eventListening(String addr, SelectableChannel ch)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_LISTENING) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_LISTENING, addr, ch));
    }

    public void eventBindFailed(String addr, int errno)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_BIND_FAILED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_BIND_FAILED, addr, errno));
    }

    public void eventAccepted(String addr, SelectableChannel ch)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_ACCEPTED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_ACCEPTED, addr, ch));
    }

    public void eventAcceptFailed(String addr, int errno)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_ACCEPT_FAILED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_ACCEPT_FAILED, addr, errno));
    }

    public void eventClosed(String addr, SelectableChannel ch)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_CLOSED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_CLOSED, addr, ch));
    }

    public void eventCloseFailed(String addr, int errno)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_CLOSE_FAILED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_CLOSE_FAILED, addr, errno));
    }

    public void eventDisconnected(String addr, SelectableChannel ch)
    {
        if ((monitorEvents & ZMQ.ZMQ_EVENT_DISCONNECTED) == 0) {
            return;
        }

        monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_DISCONNECTED, addr, ch));
    }

    protected void monitorEvent(ZMQ.Event event)
    {
        if (monitorSocket == null) {
            return;
        }

        event.write(monitorSocket);
    }

    protected void stopMonitor()
    {
        if (monitorSocket != null) {
            if ((monitorEvents & ZMQ.ZMQ_EVENT_MONITOR_STOPPED) != 0) {
                monitorEvent(new ZMQ.Event(ZMQ.ZMQ_EVENT_MONITOR_STOPPED, "", 0));
            }
            monitorSocket.close();
            monitorSocket = null;
            monitorEvents = 0;
        }
    }

    @Override
    public String toString()
    {
        return super.toString() + "[" + options.socketId + "]";
    }

    public SelectableChannel getFD()
    {
        return mailbox.getFd();
    }

    public String typeString()
    {
        switch (options.type) {
        case ZMQ.ZMQ_PAIR:
            return "PAIR";
        case ZMQ.ZMQ_PUB:
            return "PUB";
        case ZMQ.ZMQ_SUB:
            return "SUB";
        case ZMQ.ZMQ_REQ:
            return "REQ";
        case ZMQ.ZMQ_REP:
            return "REP";
        case ZMQ.ZMQ_DEALER:
            return "DEALER";
        case ZMQ.ZMQ_ROUTER:
            return "ROUTER";
        case ZMQ.ZMQ_PULL:
            return "PULL";
        case ZMQ.ZMQ_PUSH:
            return "PUSH";
        default:
            return "UNKOWN";
        }
    }

    public int errno()
    {
        return errno.get();
    }

    private static class SimpleURI
    {
        private final String protocol;
        private final String address;

        private SimpleURI(String protocol, String address)
        {
            this.protocol = protocol;
            this.address = address;
        }

        public static SimpleURI create(String value)
        {
            int pos = value.indexOf("://");
            if (pos < 0) {
                throw new IllegalArgumentException("Invalid URI: " + value);
            }
            String protocol = value.substring(0, pos);
            String address = value.substring(pos + 3);

            if (protocol.isEmpty() || address.isEmpty()) {
                throw new IllegalArgumentException("Invalid URI: " + value);
            }
            return new SimpleURI(protocol, address);
        }

        public String getProtocol()
        {
            return protocol;
        }

        public String getAddress()
        {
            return address;
        }
    }
}
