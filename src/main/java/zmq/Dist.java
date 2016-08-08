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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class Dist
{
    //  List of outbound pipes.
    //typedef array_t <zmq::pipe_t, 2> pipes_t;
    private final List<Pipe> pipes;

    //  Number of all the pipes to send the next message to.
    /**
     * 发送下一个消息给pipe的数量
     */
    private int matching;

    //  Number of active pipes. All the active pipes are located at the
    //  beginning of the pipes array. These are the pipes the messages
    //  can be sent to at the moment.
    /**
     * active的pipe数,所有active的pipe都在pipe数组的开始,这是在这个时间上,有这么多pipe可以发送消息
     */
    private int active;

    //  Number of pipes eligible for sending messages to. This includes all
    //  the active pipes plus all the pipes that we can in theory send
    //  messages to (the HWM is not yet reached), but sending a message
    //  to them would result in partial message being delivered, ie. message
    //  with initial parts missing.
    /**
     * 有资格发送消息的pipe数,包括所有active的pipe,加上在理论上可以发送消息的pipe
     * 但是发送消息给他们,结果是可能只有部分消息会被交付
     */
    private int eligible;

    //  True if last we are in the middle of a multipart message.
    private boolean more;

    public Dist()
    {
        matching = 0;
        active = 0;
        eligible = 0;
        more = false;
        pipes = new ArrayList<Pipe>();
    }

    //  Adds the pipe to the distributor object.
    /**
     * 添加pipe到分配的对象
     * 
     * @param pipe
     *
     * @author {yourname} 2016年8月8日 下午1:47:26
     */
    public void attach(Pipe pipe)
    {
        //  If we are in the middle of sending a message, we'll add new pipe
        //  into the list of eligible pipes. Otherwise we add it to the list
        //  of active pipes.
        /**
         * 如果是在一个消息的中间,我们就新增一个pipe到理论上的pipe list上,
         * 否则我们将它加入到active的pipe中.
         */
        if (more) {
            pipes.add(pipe);
            //pipes.swap (eligible, pipes.size() - 1);
            Collections.swap(pipes, eligible, pipes.size() - 1);
            eligible++;
        }
        else {
            pipes.add(pipe);
            //pipes.swap (active, pipes.size() - 1);
            Collections.swap(pipes, active, pipes.size() - 1);
            active++;
            eligible++;
        }
    }

    //  Mark the pipe as matching. Subsequent call to sendToMatching
    //  will send message also to this pipe.
    /*
     * 标记pipe是匹配的,
     */
    public void match(Pipe pipe)
    {
        int idx = pipes.indexOf(pipe);
        //  If pipe is already matching do nothing.  如果pipe已经匹配,什么也不做
        if (idx < matching) {
            return;
        }

        //  If the pipe isn't eligible, ignore it. 如果pipe理论上是不存在的,忽略掉
        if (idx >= eligible) {
            return;
        }

        //  Mark the pipe as matching.  标记该pipe为匹配
        Collections.swap(pipes, idx, matching);
        matching++;
    }

    //  Mark all pipes as non-matching.
    /**
     * 所有未匹配的pipe
     * 
     *
     * @author {yourname} 2016年8月8日 下午2:33:05
     */
    public void unmatch()
    {
        matching = 0;
    }

    //  Removes the pipe from the distributor object.
    /**
     * 移除所有pipe从关联的对象
     * 
     * @param pipe
     *
     * @author {yourname} 2016年8月8日 下午2:33:28
     */
    public void terminated(Pipe pipe)
    {
        //  Remove the pipe from the list; adjust number of matching, active and/or
        //  eligible pipes accordingly.
        if (pipes.indexOf(pipe) < matching) {
            Collections.swap(pipes, pipes.indexOf(pipe), matching - 1);
            matching--;
        }
        if (pipes.indexOf(pipe) < active) {
            Collections.swap(pipes, pipes.indexOf(pipe), active - 1);
            active--;
        }
        if (pipes.indexOf(pipe) < eligible) {
            Collections.swap(pipes, pipes.indexOf(pipe), eligible - 1);
            eligible--;
        }
        pipes.remove(pipe);
    }

    //  Activates pipe that have previously reached high watermark.
    /**
     * active pipe
     * 
     * @param pipe
     *
     * @author {yourname} 2016年8月8日 下午2:35:36
     */
    public void activated(Pipe pipe)
    {
        //  Move the pipe from passive to eligible state.  移动该pipe到理论上存在的状态
        Collections.swap(pipes, pipes.indexOf(pipe), eligible);
        eligible++;

        //  If there's no message being sent at the moment, move it to
        //  the active state.  如果没有消息被发送,移动到active状态
        if (!more) {
            Collections.swap(pipes, eligible - 1, active);
            active++;
        }
    }

    //  Send the message to all the outbound pipes.
    public boolean sendToAll(Msg msg)
    {
        matching = active;
        return sendToMatching(msg);
    }

    //  Send the message to the matching outbound pipes.
    public boolean sendToMatching(Msg msg)
    {
        //  Is this end of a multipart message?  是否是多消息的结尾
        boolean msgMore = msg.hasMore();

        //  Push the message to matching pipes.   将消息压人匹配的pipe
        distribute(msg);

        //  If mutlipart message is fully sent, activate all the eligible pipes.
        if (!msgMore) {
            active = eligible;
        }

        more = msgMore;

        return true;
    }

    //  Put the message to all active pipes.  将所有的消息设置为active
    private void distribute(Msg msg)
    {
        //  If there are no matching pipes available, simply drop the message.  如果没有批评的pipe是可用的,简单的drop掉消息
        if (matching == 0) {
            return;
        }

        for (int i = 0; i < matching; ++i) {
            if (!write(pipes.get(i), msg)) {
                --i; //  Retry last write because index will have been swapped   重试最近的write,因为index会被swap
            }
        }
    }

    public boolean hasOut()
    {
        return true;
    }

    //  Write the message to the pipe. Make the pipe inactive if writing
    //  fails. In such a case false is returned.
    /**
     * 写消息到pipe,标记pipe为inactive,如果写失败,在这个情况的返回false
     * 
     * @param pipe
     * @param msg
     * @return
     *
     * @author {yourname} 2016年8月8日 下午2:41:57
     */
    private boolean write(Pipe pipe, Msg msg)
    {
        if (!pipe.write(msg)) {
            Collections.swap(pipes, pipes.indexOf(pipe), matching - 1);
            matching--;
            Collections.swap(pipes, pipes.indexOf(pipe), active - 1);
            active--;
            Collections.swap(pipes, active, eligible - 1);
            eligible--;
            return false;
        }
        if (!msg.hasMore()) {
            pipe.flush();
        }
        return true;
    }

    public boolean checkHwm()
    {
        for (int i = 0; i < matching; ++i) {
            if (!(pipes.get(i).checkHwm())) {
                return false;
            }
        }
        return true;
    }
}
