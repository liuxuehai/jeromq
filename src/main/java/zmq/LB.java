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

public class LB
{
    //  List of outbound pipes.
    private final List<Pipe> pipes;

    //  Number of active pipes. All the active pipes are located at the
    //  beginning of the pipes array.
    /**
     * active的pipe数,所有active的pipe在pipe数组的开始
     */
    private int active;

    //  Points to the last pipe that the most recent message was sent to.
    /**
     * 指向最近的pipe,最近经常被发送的
     */
    private int current;

    //  True if last we are in the middle of a multipart message.
    /**
     * 
     */
    private boolean more;

    //  True if we are dropping current message.
    
    private boolean dropping;

    public LB()
    {
        active = 0;
        current = 0;
        more = false;
        dropping = false;

        pipes = new ArrayList<Pipe>();
    }

    public void attach(Pipe pipe)
    {
        pipes.add(pipe);
        activated(pipe);
    }

    public void terminated(Pipe pipe)
    {
        int index = pipes.indexOf(pipe);

        //  If we are in the middle of multipart message and current pipe
        //  have disconnected, we have to drop the remainder of the message.   如果在在多消息的中间,当前pipe断开连接,就drop消息的剩余部分
        if (index == current && more) {
            dropping = true;
        }

        //  Remove the pipe from the list; adjust number of active pipes
        //  accordingly.  移除pipe从list中,调整active的pipe数
        if (index < active) {
            active--;
            Collections.swap(pipes, index, active);
            if (current == active) {
                current = 0;
            }
        }
        pipes.remove(pipe);
    }

    public void activated(Pipe pipe)
    {
        //  Move the pipe to the list of active pipes.   移动pipe到active的
        Collections.swap(pipes, pipes.indexOf(pipe), active);
        active++;
    }

    public boolean send(Msg msg, ValueReference<Integer> errno)
    {
        //  Drop the message if required. If we are at the end of the message
        //  switch back to non-dropping mode.
        // 发送消息如果需要,如果是消息最后切换回不 non-dropping 模式
        if (dropping) {
            more = msg.hasMore();
            dropping = more;

            // msg_.close();
            return true;
        }

        while (active > 0) {
            if (pipes.get(current).write(msg)) {
                break;
            }

            assert (!more);
            active--;
            if (current < active) {
                Collections.swap(pipes, current, active);
            }
            else {
                current = 0;
            }
        }

        //  If there are no pipes we cannot send the message.  如果没有pipe,那么我们就不能发送消息
        if (active == 0) {
            errno.set(ZError.EAGAIN);
            return false;
        }

        //  If it's final part of the message we can flush it downstream and
        //  continue round-robining (load balance).
        //  如果是最后的部分,就可以flush出去,同时继续负载均衡
        
        more = msg.hasMore();
        if (!more) {
            pipes.get(current).flush();
            if (++current >= active) {
                current = 0;
            }
        }

        return true;
    }

    public boolean hasOut()
    {
        //  If one part of the message was already written we can definitely
        //  write the rest of the message.
        // 如果部分消息已经写入了，那么我们就可以写入余下的消息
        if (more) {
            return true;
        }

        while (active > 0) {
            //  Check whether a pipe has room for another message.  检查是否有多余的room给another消息
            if (pipes.get(current).checkWrite()) {
                return true;
            }

            //  Deactivate the pipe.
            active--;
            Collections.swap(pipes, current, active);
            if (current == active) {
                current = 0;
            }
        }

        return false;
    }
}
