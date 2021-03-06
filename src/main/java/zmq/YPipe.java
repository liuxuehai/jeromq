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

import java.util.concurrent.atomic.AtomicInteger;

public class YPipe<T>
{
    //  Allocation-efficient queue to store pipe items.
    //  Front of the queue points to the first prefetched item, back of
    //  the pipe points to last un-flushed item. Front is used only by
    //  reader thread, while back is used only by writer thread.
    /**
     * 分配有效的queue去存储pipe item
     * queue的前端指向第一个预取的item,back指向最后为结束的item
     * 前端只用于reader线程,而back只用于writer线程
     */
    private final YQueue<T> queue;

    //  Points to the first un-flushed item. This variable is used
    //  exclusively by writer thread.
    /**
     * 指向第一个未结束的item,该参数只被writer线程使用
     */
    private int w;

    //  Points to the first un-prefetched item. This variable is used
    //  exclusively by reader thread.
    /**
     * 指向第一个未预取的item,只被reader线程使用
     */
    private int r;

    //  Points to the first item to be flushed in the future.
    /**
     * 指向第一个item,未来被flush的
     */
    private int f;

    //  The single point of contention between writer and reader thread.
    //  Points past the last flushed item. If it is NULL,
    //  reader is asleep. This pointer should be always accessed using
    //  atomic operations.
    /**
     * 
     */
    private final AtomicInteger c;

    public YPipe(int qsize)
    {
        queue = new YQueue<T>(qsize);
        int pos = queue.backPos();
        f = pos;
        r = pos;
        w = pos;
        c = new AtomicInteger(pos);
    }

    //  Write an item to the pipe.  Don't flush it yet. If incomplete is
    //  set to true the item is assumed to be continued by items
    //  subsequently written to the pipe. Incomplete items are never
    //  flushed down the stream.
    /**
     * 将item写入pipe,不刷新,如果设置为为结束,则下次会继续写入这个pipe,
     * 
     * @param value
     * @param incomplete
     *
     * @author {yourname} 2016年7月28日 下午2:30:15
     */
    public void write(final T value, boolean incomplete)
    {
        //  Place the value to the queue, add new terminator element.
        // 将数据放入queue中,和一个新的终结元素
        queue.push(value);

        //  Move the "flush up to here" poiter.
        if (!incomplete) {
            f = queue.backPos();
        }
    }

    //  Pop an incomplete item from the pipe. Returns true is such
    //  item exists, false otherwise.
    /**
     * 取出一个未结束的item从pipe总,如果该元素存在就返回,
     * 
     * @return
     *
     * @author {yourname} 2016年7月28日 下午4:01:27
     */
    public T unwrite()
    {
        if (f == queue.backPos()) {
            return null;
        }
        queue.unpush();
        return queue.back();
    }

    //  Flush all the completed items into the pipe. Returns false if
    //  the reader thread is sleeping. In that case, caller is obliged to
    //  wake the reader up before using the pipe again.
    /**
     * 刷新所有结束的item到pipe,如果reader线程sleeping就返回false
     * 在这种情况下,调用者有义务去唤醒reader在下次使用pipe之前
     * @return
     *
     * @author {yourname} 2016年7月28日 下午4:02:16
     */
    public boolean flush()
    {
        //  If there are no un-flushed items, do nothing.
        //如果不存在未flush的item,什么都不做
        if (w == f) {
            return true;
        }

        //  Try to set 'c' to 'f'.
        if (!c.compareAndSet(w, f)) {
            //  Compare-and-swap was unseccessful because 'c' is NULL.
            //  This means that the reader is asleep. Therefore we don't
            //  care about thread-safeness and update c in non-atomic
            //  manner. We'll return false to let the caller know
            //  that reader is sleeping.
            /**
             * CAS 失败的原因是 c 是null,这意味着reader是asleep的,
             * 这样我们不用关心线程不安全,在非原子操作下更新c
             * 返回false让调用者知道reader在sleep
             */
            c.set(f);
            w = f;
            return false;
        }

        //  Reader is alive. Nothing special to do now. Just move
        //  the 'first un-flushed item' pointer to 'f'.
        w = f;
        return true;
    }

    //  Check whether item is available for reading.
    public boolean checkRead()
    {
        //  Was the value prefetched already? If so, return.
        int h = queue.frontPos();
        if (h != r) {
             return true;
        }

        //  There's no prefetched value, so let us prefetch more values.
        //  Prefetching is to simply retrieve the
        //  pointer from c in atomic fashion. If there are no
        //  items to prefetch, set c to -1 (using compare-and-swap).
        if (c.compareAndSet(h, -1)) {
             // nothing to read, h == r must be the same
        }
        else {
            // something to have been written
             r = c.get();
        }

        //  If there are no elements prefetched, exit.
        //  During pipe's lifetime r should never be NULL, however,
        //  it can happen during pipe shutdown when items
        //  are being deallocated.
        if (h == r || r == -1) {
            return false;
        }

        //  There was at least one value prefetched.
        return true;
    }

    //  Reads an item from the pipe. Returns false if there is no value.
    //  available.
    public T read()
    {
        //  Try to prefetch a value.
        if (!checkRead()) {
            return null;
        }

        //  There was at least one value prefetched.
        //  Return it to the caller.

        return queue.pop();
    }

    //  Applies the function fn to the first elemenent in the pipe
    //  and returns the value returned by the fn.
    //  The pipe mustn't be empty or the function crashes.
    public T probe()
    {
        boolean rc = checkRead();
        assert (rc);

        return queue.front();
    }
}
