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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

//  Base class for objects forming a part of ownership hierarchy.
//  It handles initialisation and destruction of such objects.
/**
 * 基础类给对象形成所有者关系的一部分
 * 处理初始化和销毁
 * 
 * @since 1.0.0
 * @version $Id$
 */
abstract class Own extends ZObject
{
    protected final Options options;

    //  True if termination was already initiated. If so, we can destroy
    //  the object if there are no more child objects or pending term acks.
    /**
     * 如果termination已经初始化了，就为true,如果没有更多的子对象,或pending term的确认,就可以销毁该对象
     */
    private boolean terminating;

    //  Sequence number of the last command sent to this object.
    /**
     * 发送给该对象的命令数
     */
    private final AtomicLong sendSeqnum;

    //  Sequence number of the last command processed by this object.
    /**
     * 该对象处理的命令数
     */
    private long processedSeqnum;

    //  Socket owning this object. It's responsible for shutting down
    //  this object.
    /**
     * socket拥有该对象,负责关闭该对象
     */
    private Own owner;

    //  List of all objects owned by this socket. We are responsible
    //  for deallocating them before we quit.
    //typedef std::set <own_t*> owned_t;
    /**
     * 该socket所拥有的对象,负责销毁他们在quit之前
     */
    private final Set<Own> owned;

    //  Number of events we have to get before we can destroy the object.
    /**
     * 在销毁对象之前,所获取的世界数
     */
    private int termAcks;

    //  Note that the owner is unspecified in the constructor.
    //  It'll be supplied later on when the object is plugged in.

    //  The object is not living within an I/O thread. It has it's own
    //  thread outside of 0MQ infrastructure.
    /**
     * 所有者是非指定的,会在稍后指定当该对象plug之后
     * 该对象不存在于i/o线程,有自己的线程在0MQ基础之外
     * @param parent
     * @param tid
     */
    public Own(Ctx parent, int tid)
    {
        super(parent, tid);
        terminating = false;
        sendSeqnum = new AtomicLong(0);
        processedSeqnum = 0;
        owner = null;
        termAcks = 0;

        options = new Options();
        owned = new HashSet<Own>();
    }

    //  The object is living within I/O thread.
    /**
     * 该对象在i/o线程中
     * @param ioThread
     * @param options
     */
    public Own(IOThread ioThread, Options options)
    {
        super(ioThread);
        this.options = options;
        terminating = false;
        sendSeqnum = new AtomicLong(0);
        processedSeqnum = 0;
        owner = null;
        termAcks = 0;

        owned = new HashSet<Own>();
    }

    public abstract void destroy();

    //  A place to hook in when phyicallal destruction of the object
    //  is to be delayed.
    /**
     * 
     * 
     *
     * @author {yourname} 2016年8月11日 上午11:14:49
     */
    protected void processDestroy()
    {
        destroy();
    }

    private void setOwner(Own owner)
    {
        assert (this.owner == null);
        this.owner = owner;
    }

    //  When another owned object wants to send command to this object
    //  it calls this function to let it know it should not shut down
    //  before the command is delivered.
    /*
     *当其他owned的对象希望发送命令给该对象,
     *调用该方法让它知道不应该关闭在命令分发之前 
     */
    void incSeqnum()
    {
        //  This function may be called from a different thread!
        sendSeqnum.incrementAndGet();
    }

    protected void processSeqnum()
    {
        //  Catch up with counter of processed commands.   记录处理的命令数
        processedSeqnum++;

        //  We may have catched up and still have pending terms acks.  
        checkTermAcks();
    }

    //  Launch the supplied object and become its owner.
    /**
     * 根据给定参数启动该对象,并且成为他的owner
     * 
     * @param object
     *
     * @author {yourname} 2016年8月1日 上午9:54:41
     */
    protected void launchChild(Own object)
    {
        //  Specify the owner of the object.  指定对象的owner
        object.setOwner(this);

        //  Plug the object into the I/O thread.
        // plug 这个对象到i/o线程
        sendPlug(object);

        //  Take ownership of the object. 获取该对象的所有权
        
        sendOwn(this, object);
    }

    //  Terminate owned object
    /**
     * 终结所有者对象
     * 
     * @param object
     *
     * @author {yourname} 2016年8月11日 上午11:15:28
     */
    protected void termChild(Own object)
    {
        processTermReq(object);
    }

    @Override
    protected void processTermReq(Own object)
    {
        //  When shutting down we can ignore termination requests from owned
        //  objects. The termination request was already sent to the object.
        /**
         * 可以忽略termination请求从owned对象,该termination已经发生给这个对象
         */
        if (terminating) {
            return;
        }

        //  If I/O object is well and alive let's ask it to terminate.

        //  If not found, we assume that termination request was already sent to
        //  the object so we can safely ignore the request.
        /**
         * 所有者是否拥有该对象,没有就忽略
         */
        if (!owned.contains(object)) {
            return;
        }

        owned.remove(object);
        registerTermAcks(1);

        //  Note that this object is the root of the (partial shutdown) thus, its
        //  value of linger is used, rather than the value stored by the children.
        /**
         * 
         */
        sendTerm(object, options.linger);
    }

    protected void processOwn(Own object)
    {
        //  If the object is already being shut down, new owned objects are
        //  immediately asked to terminate. Note that linger is set to zero.
        /**
         * 如果对象已经关闭,新的owned对象之间请求终结
         */
        if (terminating) {
            registerTermAcks(1);
            sendTerm(object, 0);
            return;
        }

        //  Store the reference to the owned object.  添加对象到owned
        owned.add(object);
    }

    //  Ask owner object to terminate this object. It may take a while
    //  while actual termination is started. This function should not be
    //  called more than once.
    /**
     * 请求拥有对象终结该对象,需要等待实际termination开始,该方法不应该多次调用
     * 
     *
     * @author {yourname} 2016年8月11日 下午2:18:48
     */
    protected void terminate()
    {
        //  If termination is already underway, there's no point
        //  in starting it anew.  如果termination已经开始,直接返回
        if (terminating) {
            return;
        }

        //  As for the root of the ownership tree, there's noone to terminate it,
        //  so it has to terminate itself.   如果没有owner,那么久只需要终结自己
        if (owner == null) {
            processTerm(options.linger);
            return;
        }

        //  If I am an owned object, I'll ask my owner to terminate me.   如果是被owner的对象，就请求owner终结自己
        sendTermReq(owner, this);
    }

    //  Returns true if the object is in process of termination.
    /**
     * 查看是否在termination处理中
     * 
     * @return
     *
     * @author {yourname} 2016年8月11日 下午2:25:08
     */
    protected boolean isTerminating()
    {
        return terminating;
    }

    //  Term handler is protocted rather than private so that it can
    //  be intercepted by the derived class. This is useful to add custom
    //  steps to the beginning of the termination process.
    /**
     * 
     * {@inheritDoc}
     */
    @Override
    protected void processTerm(int linger)
    {
        //  Double termination should never happen.  双重termination应该不会发生
        assert (!terminating);

        //  Send termination request to all owned objects.   发送termination请求到所有owned的对象
        for (Own it : owned) {
            sendTerm(it, linger);
        }
        registerTermAcks(owned.size());
        owned.clear();

        //  Start termination process and check whether by chance we cannot
        //  terminate immediately.   启动termination处理,检查是否不能直接terminate
        terminating = true;
        checkTermAcks();
    }

    //  Use following two functions to wait for arbitrary events before
    //  terminating. Just add number of events to wait for using
    //  register_tem_acks functions. When event occurs, call
    //  remove_term_ack. When number of pending acks reaches zero
    //  object will be deallocated.
    /**
     * 
     * 下面2个方法在termination之前等待任意事件,
     * 只是添加事件的数量去等待使用register_tem_acks方法
     * 当事件发送了,使用remove_term_ack方法
     * 当pending ack 到达0则该对象会被销毁
     * @param count
     *
     * @author {yourname} 2016年8月11日 下午2:28:41
     */
    public void registerTermAcks(int count)
    {
        termAcks += count;
    }

    public void unregisterTermAck()
    {
        assert (termAcks > 0);
        termAcks--;

        //  This may be a last ack we are waiting for before termination...
        checkTermAcks();
    }

    @Override
    protected void processTermAck()
    {
        unregisterTermAck();
    }

    private void checkTermAcks()
    {
        if (terminating && processedSeqnum == sendSeqnum.get() &&
              termAcks == 0) {
            //  Sanity check. There should be no active children at this point.  
            // 完整性检查,应该没有active的子对象在这个节点
            assert (owned.isEmpty());

            //  The root object has nobody to confirm the termination to.
            //  Other nodes will confirm the termination to the owner.
            //  root对象没有其他对象去确认终结,其他节点会确认终结该owner
            if (owner != null) {
                sendTermAck(owner);
            }

            //  Deallocate the resources.
            processDestroy();
        }
    }
}
