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

public enum Config
{
    //  Number of new messages in message pipe needed to trigger new memory
    //  allocation. Setting this parameter to 256 decreases the impact of
    //  memory allocation by approximately 99.6%
    /**
     * 新消息的数量在消息pipe需要发起新的内存分配是,
     * 设置为256 降低内存分配影响到99.6%
     */
    MESSAGE_PIPE_GRANULARITY (256),

    //  Commands in pipe per allocation event.
    /**
     * 分配事件中的管道中的命令
     */
    COMMAND_PIPE_GRANULARITY (16),

    //  Determines how often does socket poll for new commands when it
    //  still has unprocessed messages to handle. Thus, if it is set to 100,
    //  socket will process 100 inbound messages before doing the poll.
    //  If there are no unprocessed messages available, poll is done
    //  immediately. Decreasing the value trades overall latency for more
    //  real-time behaviour (less latency peaks).
    /**
     * 决定socket poll一个新消息当还有为完成的消息时的频率,
     * 设置为100,socket会处理100个inbound消息在处理poll之前,
     * 如果没有可以处理的消息,poll直接结束,
     * 减少这个数字将会更实时,减少延迟(低延迟)
     */
    INBOUND_POLL_RATE (100),

    //  Maximal batching size for engines with receiving functionality.
    //  So, if there are 10 messages that fit into the batch size, all of
    //  them may be read by a single 'recv' system call, thus avoiding
    //  unnecessary network stack traversals.
    /**
     * 最大的批量处理数 接收功能的engine,
     * 如果有10的消息fit这个批次数,那么所有的消息可能会在一个recv调用里面读取,
     * 这样避免不必要的网络堆栈遍历
     */
    IN_BATCH_SIZE (8192),

    //  Maximal batching size for engines with sending functionality.
    //  So, if there are 10 messages that fit into the batch size, all of
    //  them may be written by a single 'send' system call, thus avoiding
    //  unnecessary network stack traversals.
    /**
     * 最大批量处理数 发送功能的engine
     * 如果有10个消息fit这个批次数,所有的消息都会写出在一个 send系统调用
     * 这样避免不必要的网络堆栈遍历
     */
    OUT_BATCH_SIZE (8192),

    //  Maximal delta between high and low watermark.
    /**
     * 最大的delta 在高水位和低水位间
     */
    MAX_WM_DELTA (1024),

    //  Maximum number of events the I/O thread can process in one go.
    /**
     * 最大的i/o线程数时间在一个处理中.
     */
    MAX_IO_EVENTS (256),

    //  Maximal delay to process command in API thread (in CPU ticks).
    //  3,000,000 ticks equals to 1 - 2 milliseconds on current CPUs.
    //  Note that delay is only applied when there is continuous stream of
    //  messages to process. If not so, commands are processed immediately.
    /**
     * 最大的延迟去处理命令在api线程(在cpu时钟)
     * 3,000,000个时钟意味着1-2ms在当前的cpu中
     * 这个延迟只用在消息处理持续的消息流时被处理
     * 如果不是,则是实时处理
     */
    MAX_COMMAND_DELAY (3000000),

    //  Low-precision clock precision in CPU ticks. 1ms. Value of 1000000
    //  should be OK for CPU frequencies above 1GHz. If should work
    //  reasonably well for CPU frequencies above 500MHz. For lower CPU
    //  frequencies you may consider lowering this value to get best
    //  possible latencies.
    /**
     * 低精度的时钟精度在cpu tick,1ms 1000000应该在1GHz上是ok的
     * 减少这个数字可以得到更好的低延迟
     */
    CLOCK_PRECISION  (1000000),

    //  Maximum transport data unit size for PGM (TPDU).
    /**
     * 最大的传输数据unit大小 PGM
     */
    PGM_MAX_TPDU  (1500),

    //  On some OSes the signaler has to be emulated using a TCP
    //  connection. In such cases following port is used.
    /**
     * 在部分os上signaler是通过tcp连接的,在这个情况下使用该端口
     */
    SIGNALER_PORT (5905);

    private final int value;

    private Config(int value)
    {
        this.value = value;
    }

    public int getValue()
    {
        return value;
    }
}
