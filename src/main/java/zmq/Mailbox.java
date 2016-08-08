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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Mailbox
        implements Closeable
{
    //  The pipe to store actual commands.
    /**
     * 保存实际的command
     */
    private final YPipe<Command> cpipe;

    //  Signaler to pass signals from writer thread to reader thread.
    /**
     * 发送信号从writer线程到reader线程
     */
    private final Signaler signaler;

    //  There's only one thread receiving from the mailbox, but there
    //  is arbitrary number of threads sending. Given that ypipe requires
    //  synchronised access on both of its endpoints, we have to synchronise
    //  the sending side.
    /**
     *  只用一个线程从信箱里获取数据,但是有很多线程的发送,所以pipe需要同步控制
     *  
     */
    private final Lock sync;

    //  True if the underlying pipe is active, ie. when we are allowed to
    //  read commands from it.
    /**
     * 如果底层的pipe是active的那么为true,例如,当我们允许读取命令时
     */
    private boolean active;

    // mailbox name, for better debugging
    /**
     * 信箱名称
     */
    private final String name;

    public Mailbox(String name)
    {
        cpipe = new YPipe<Command>(Config.COMMAND_PIPE_GRANULARITY.getValue());
        sync = new ReentrantLock();
        signaler = new Signaler();

        //  Get the pipe into passive state. That way, if the users starts by
        //  polling on the associated file descriptor it will get woken up when
        //  new command is posted.

        // 获取pipe的
        Command cmd = cpipe.read();
        assert (cmd == null);
        active = false;

        this.name = name;
    }

    public SelectableChannel getFd()
    {
        return signaler.getFd();
    }

    public void send(final Command cmd)
    {
        boolean ok = false;
        sync.lock();
        try {
            cpipe.write(cmd, false);
            ok = cpipe.flush();
        }
        finally {
            sync.unlock();
        }

        if (!ok) {
            signaler.send();
        }
    }

    public Command recv(long timeout)
    {
        Command cmd = null;
        //  Try to get the command straight away.
        if (active) {
            cmd = cpipe.read();
            if (cmd != null) {
                return cmd;
            }

            //  If there are no more commands available, switch into passive state.
            // 如果没有command,切换到被动状态
            active = false;
            signaler.recv();
        }

        //  Wait for signal from the command sender.   等待从命令sender 的信号
        boolean rc = signaler.waitEvent(timeout);
        if (!rc) {
            return null;
        }

        //  We've got the signal. Now we can switch into active state.   获取到信号,就将状态切换到激活状态
        active = true;

        //  Get a command.
        cmd = cpipe.read();
        assert (cmd != null);

        return cmd;
    }

    @Override
    public void close() throws IOException
    {
        //  TODO: Retrieve and deallocate commands inside the cpipe.

        // Work around problem that other threads might still be in our
        // send() method, by waiting on the mutex before disappearing.
        sync.lock();
        sync.unlock();

        signaler.close();
    }

    @Override
    public String toString()
    {
        return super.toString() + "[" + name + "]";
    }
}
