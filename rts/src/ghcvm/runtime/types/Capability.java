package ghcvm.runtime.types;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Deque;
import java.util.Stack;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import ghcvm.runtime.types.Task.InCall;
import static ghcvm.runtime.Rts.*;
import static ghcvm.runtime.RtsMessages.*;
import static ghcvm.runtime.Rts.ExitCode.*;
import static ghcvm.runtime.RtsScheduler.*;
import static ghcvm.runtime.RtsScheduler.SchedulerState.*;
import static ghcvm.runtime.RtsScheduler.SchedulerStatus.*;
import static ghcvm.runtime.types.StgTSO.*;
import static ghcvm.runtime.types.StgTSO.WhatNext.*;
import static ghcvm.runtime.types.StgTSO.WhyBlocked.*;
import static ghcvm.runtime.closure.StgContext.*;
import static ghcvm.runtime.closure.StgContext.ReturnCode.*;
import static ghcvm.runtime.RtsScheduler.RecentActivity.*;
import static ghcvm.runtime.Interpreter.*;
import ghcvm.runtime.*;
import ghcvm.runtime.thunk.*;
import ghcvm.runtime.thread.*;
import ghcvm.runtime.closure.*;
import ghcvm.runtime.exception.*;
import ghcvm.runtime.prim.*;
import ghcvm.runtime.stm.*;
import ghcvm.runtime.message.*;
import ghcvm.runtime.stackframe.*;
import static ghcvm.runtime.stackframe.StackFrame.MarkFrameResult;
import static ghcvm.runtime.stackframe.StackFrame.MarkFrameResult.*;
import static ghcvm.runtime.stm.STM.EntrySearchResult;
import static ghcvm.runtime.stm.StgTRecChunk.TREC_CHUNK_NUM_ENTRIES;

public final class Capability {
    public static final int MAX_SPARE_WORKERS = 6;
    public static int nCapabilities;
    public static int enabledCapabilities;
    public static Capability mainCapability;
    public static Capability lastFreeCapability;
    public static List<Capability> capabilities;
    public static SyncType pendingSync = SyncType.SYNC_NONE;

    public enum SyncType {
        SYNC_NONE(0),
        SYNC_GC_SEQ(1),
        SYNC_GC_PAR(2),
        SYNC_OTHER(3);

        private int type;
        SyncType(int type) {
            this.type = type;
        }
    }

    public static void init() {
        if (RtsFlags.ModeFlags.threaded) {
            nCapabilities = 0;
            moreCapabilities(0, RtsFlags.ParFlags.nNodes);
            nCapabilities = RtsFlags.ParFlags.nNodes;
        } else {
            nCapabilities = 1;
            mainCapability = new Capability(0);
            capabilities.add(0, mainCapability);
        }
        enabledCapabilities = nCapabilities;
        lastFreeCapability = capabilities.get(0);
    }

    public static void moreCapabilities (int from, int to) {
        ArrayList<Capability> oldCapabilities = (ArrayList<Capability>) capabilities;
        capabilities = new ArrayList<Capability>(to);
        if (to == 1) {
            mainCapability = new Capability(0);
            capabilities.add(0, mainCapability);
        } else {
            for (int i = 0; i < to; i++) {
                if (i < from) {
                    capabilities.add(i, oldCapabilities.get(i));
                } else {
                    capabilities.add(i, new Capability(i));
                }
            }
        }
    }

    public int no;
    public Lock lock = new ReentrantLock();
    public StgContext context = new StgContext();
    public Task runningTask;
    public boolean inHaskell;
    public int idle;
    public boolean disabled;
    public Deque<StgTSO> runQueue = new ArrayDeque<StgTSO>();
    public Deque<InCall> suspendedJavaCalls = new ArrayDeque<InCall>();
    public boolean contextSwitch;
    public boolean interrupt;
    public Queue<Task> spareWorkers = new ArrayBlockingQueue<Task>(MAX_SPARE_WORKERS);
    public Deque<Task> returningTasks = new ArrayDeque<Task>();
    public Deque<Message> inbox = new ArrayDeque<Message>();
    public SparkPool sparks = new SparkPool();
    public SparkCounters sparkStats = new SparkCounters();
    public List<StgWeak> weakPtrList = new ArrayList<StgWeak>();
    public Stack<StgTRecChunk> freeTrecChunks = new Stack<StgTRecChunk>();
    public int ioManagerControlWrFd; // Not sure if this is necessary

    public Capability(int i) {
        this.no = i;
    }


    public final Capability schedule(Task task) {
        Capability cap = this;
        boolean threaded = RtsFlags.ModeFlags.threaded;
        while (true) {
            if (cap.inHaskell) {
                errorBelch("schedule: re-entered unsafely.\n" +
                           "    Perhaps a 'foreign import unsafe' should be 'safe'?");
                stgExit(EXIT_FAILURE);
            }

            switch (schedulerState) {
                case SCHED_RUNNING:
                    break;
                case SCHED_INTERRUPTING:
                    cap = null;//TODO: scheduleDoGC(cap, task, false);
                case SCHED_SHUTTING_DOWN:
                    if (!task.isBound() && cap.emptyRunQueue()) {
                        return cap;
                    }
                    break;
                default:
                    barf("sched state: " + schedulerState);
            }

            cap = cap.findWork();
            if (threaded) {
                cap.pushWork(task);
            }
            cap = cap.detectDeadlock(task);
            if (threaded) {
                cap = cap.scheduleYield(task);
                if (cap.emptyRunQueue()) continue;
            }

            StgTSO t = cap.popRunQueue();
            if (threaded) {
                Task.InCall bound = t.bound;
                if (bound != null) {
                    if (bound.task() != task) {
                        cap.pushOnRunQueue(t);
                        continue;
                    }
                } else {
                    if (task.incall.tso != null) {
                        cap.pushOnRunQueue(t);
                        continue;
                    }
                }
            }

            if (schedulerState.compare(SCHED_INTERRUPTING) >= 0 &&
                !(t.whatNext == ThreadComplete || t.whatNext == ThreadKilled)) {
                t.delete();
            }

            if (threaded) {
                if (cap.disabled && t.bound != null) {
                    Capability destCap = capabilities.get(cap.no % enabledCapabilities);
                    cap.migrateThread(t, destCap);
                    continue;
                }
            }

            if (RtsFlags.ConcFlags.ctxtSwitchTicks == 0
                && !cap.emptyThreadQueues()) {
                cap.contextSwitch = true;
            }

            boolean runThread = true;
            while (runThread) {
                runThread = false;

                StgContext context = cap.context;
                context.currentTSO = t;
                context.myCapability = cap;
                cap.interrupt = false;
                cap.inHaskell = true;
                cap.idle = 0;

                WhatNext prevWhatNext = t.whatNext;
                // errno

                switch (recentActivity) {
                case DoneGC:
                    //xchg(recent_activity, ACTIVITy_YES)
                    //if (prev == ACTIVITY_DONE_GC)
                    // startTimer
                    // stuff here
                    break;
                case Inactive:
                    break;
                default:
                    recentActivity = Yes;
                }

                ReturnCode ret = HeapOverflow;
                switch (prevWhatNext) {
                case ThreadKilled:
                case ThreadComplete:
                    ret = ThreadFinished;
                    break;
                case ThreadRunGHC:
                    context.myCapability = cap;
                    ListIterator<StackFrame> sp = t.sp;
                    // Shift sp to the beginning of the stack
                    while (sp.hasPrevious()) {
                        sp.previous();
                    }
                    context.sp = sp;
                    try {
                        sp.next().enter(context);
                    } catch (ThreadYieldException e) {

                    } catch (StgReturnException e) {
                        // Ensure that the StgContext objects match.
                    } finally {
                        // TOOD: is this the right way to grab the context?
                        ret = context.ret;
                        cap = context.myCapability;
                    }
                    break;
                case ThreadInterpret:
                    cap = interpretBCO(cap);
                    ret = cap.context.ret;
                    break;
                default:
                    barf("schedule: invalid what_next field");
                }

                cap.inHaskell = false;
                t = cap.context.currentTSO;
                cap.context.currentTSO = null;

                if (ret == ThreadBlocked) {
                    if (t.whyBlocked == BlockedOnBlackHole) {
                        //StgTSO owner = blackHoleOwner((MessageBlackHole)t.blockInfo.bh);

                    } else {
                    }
                } else {
                }

                boolean readyToGC = false;

                cap.postRunThread(t);
                switch (ret) {
                case HeapOverflow:
                    readyToGC = cap.handleHeapOverflow(t);
                    break;
                case StackOverflow:
                    cap.threadStackOverflow(t);
                    cap.pushOnRunQueue(t);
                    break;
                case ThreadYielding:
                    if (cap.handleYield(t, prevWhatNext)) {
                        runThread = true;
                    }
                    break;
                case ThreadBlocked:
                    t.handleThreadBlocked();
                    break;
                case ThreadFinished:
                    if (cap.handleThreadFinished(task, t)) return cap;
                    break;
                default:
                    barf("schedule: invalid thread return code %d", ret);
                }

                if (readyToGC && !runThread) {
                    //cap = scheduleDoGc(cap, task, false);
                }
            }
        }
    }

    public final void migrateThread(StgTSO tso, Capability to) {
        tso.whyBlocked = ThreadMigrating;
        tso.cap = to;
        tryWakeupThread(tso);
    }

    public final void appendToRunQueue(StgTSO tso) {
        runQueue.offer(tso);
    }

    public final void pushOnRunQueue(StgTSO tso) {
        runQueue.offerFirst(tso);
    }

    public final StgTSO popRunQueue() {
        return runQueue.poll();
    }

    public final Capability scheduleYield(Task task) {
        Capability cap = this;
        boolean didGcLast = false;
        if (!cap.shouldYield(task,false) &&
            (!cap.emptyRunQueue() ||
             !cap.emptyInbox() ||
             schedulerState.compare(SCHED_INTERRUPTING) >= 0)) {
            return cap;
        }

        do {
            YieldResult result = cap.yield(task, !didGcLast);
            didGcLast = result.didGcLast;
            cap = result.cap;
        } while (cap.shouldYield(task, didGcLast));
        return cap;
    }

    public static class YieldResult {
        public final Capability cap;
        public final boolean didGcLast;

        public YieldResult(final Capability cap, final boolean didGcLast) {
            this.cap = cap;
            this.didGcLast = didGcLast;
        }
    }

    public final YieldResult yield(Task task, final boolean gcAllowed) {
        Capability cap = this;
        boolean didGcLast = false;

        if ((pendingSync == SyncType.SYNC_GC_PAR) && gcAllowed) {
            cap.gcWorkerThread();
            if (task.cap == cap) {
                didGcLast = true;
            }
        }

        task.wakeup = false;
        Lock l = cap.lock;
        l.lock();
        try {
            if (task.isWorker()) {
                cap.enqueueWorker();
            }
            cap.release_(false);
            if (task.isWorker() || task.isBound()) {
                l.unlock();
                cap = task.waitForWorkerCapability();
            } else {
                cap.newReturningTask(task);
                l.unlock();
                cap = task.waitForReturnCapability();
            }
        } finally {
            l.unlock();
        }
        return new YieldResult(cap,didGcLast);
    }

    public final boolean shouldYield(Task task, boolean didGcLast) {
        // TODO: Refine this condition
        return ((pendingSync != SyncType.SYNC_NONE && !didGcLast) ||
                !returningTasks.isEmpty() ||
                (!emptyRunQueue() && (task.incall.tso == null
                                          ? peekRunQueue().bound != null
                                          : peekRunQueue().bound != task.incall)));
    }

    public final StgTSO peekRunQueue() {
        return runQueue.peek();
    }

    public final void pushWork(Task task) {
        // TODO: Incorporate the spark logic
        if (!RtsFlags.ParFlags.migrate) return;
        if (emptyRunQueue()) {
            if (sparks.size() < 2) return;
        } else {
            if (singletonRunQueue() && sparks.size() < 1) return;
        }
        ArrayList<Capability> freeCapabilities = new ArrayList(capabilities.size());
        for (Capability cap: capabilities) {
            if (cap != this && !cap.disabled && cap.tryGrab(task)) {
                if (!cap.emptyRunQueue()
                    || !cap.returningTasks.isEmpty()
                    || !cap.emptyInbox()) {
                    cap.release();
                } else {
                    freeCapabilities.add(cap);
                }
            }
        }
        int nFreeCapabilities = freeCapabilities.size();
        if (nFreeCapabilities > 0) {
            int i = 0;
            if (!emptyRunQueue()) {
                Deque<StgTSO> newRunQueue = new ArrayDeque<StgTSO>(1);
                newRunQueue.offer(popRunQueue());
                StgTSO t = peekRunQueue();
                StgTSO next = null;
                for (; t != null; t = next) {
                    next = popRunQueue();
                    if (t.bound == task.incall
                        || t.isFlagLocked()) {
                        newRunQueue.offer(t);
                    } else if (i == nFreeCapabilities) {
                        i = 0;;
                        newRunQueue.offer(t);
                    } else {
                        Capability freeCap = freeCapabilities.get(i);
                        freeCap.appendToRunQueue(t);
                        if (t.bound != null) {
                            t.bound.task().cap = freeCap;
                        }
                        t.cap = freeCap;
                        i++;
                    }
                }
                runQueue = newRunQueue;
            }

            for (Capability freeCap: freeCapabilities) {
                task.cap = freeCap;
                freeCap.releaseAndWakeup();
            }
        }
        task.cap = this;
    }

    public final Capability detectDeadlock(Task task) {
        Capability cap = this;
        boolean threaded = RtsFlags.ModeFlags.threaded;
        if (cap.emptyThreadQueues()) {
            if (threaded) {
                if (recentActivity != Inactive) return cap;
                //scheduleDoGC

            } else {
                if (task.incall.tso != null) {
                    switch (task.incall.tso.whyBlocked) {
                        case BlockedOnSTM:
                        case BlockedOnBlackHole:
                        case BlockedOnMsgThrowTo:
                        case BlockedOnMVar:
                        case BlockedOnMVarRead:
                            cap.throwToSingleThreaded(task.incall.tso,
                                                  null /* TODO: nonTermination_closure */
                                                  );
                            return cap;
                        default:
                            barf("deadlock: main thread blocked in a strange way");
                    }
                }
                return cap;
            }
        }
        return cap;
    }

    public final Capability findWork() {
        Capability cap = this;
        startSignalHandlers();

        if (!RtsFlags.ModeFlags.threaded) {
            checkBlockedThreads();
        } else {
            cap = processInbox();
            if (emptyRunQueue()) activateSpark();
        }

        return cap;
    }

    public final void activateSpark() {}

    public final void startSignalHandlers() {
        if (!RtsFlags.ModeFlags.threaded && RtsFlags.ModeFlags.userSignals) {

            if (RtsFlags.MiscFlags.installSignalHandlers /* && signals_pending() */) {
                // start signal handlers
                //addShutdownHook
            }
        }
    }

    public final void checkBlockedThreads() {
        if (!emptyBlockedQueue() || !emptySleepingQueue()) {
            RtsIO.awaitEvent(emptyRunQueue());
        }
    }

    public final Capability processInbox() {
        Capability cap = this;
        while (!cap.emptyInbox()) {
            // scheduleDoGC
            Lock l = cap.lock;
            if (l.tryLock()) {
                Deque<Message> inbox = cap.inbox;
                try {
                    cap.inbox.clear();
                } finally {
                    l.unlock();
                }
                for (Message m: inbox) {
                    m.execute(this);
                }
            } else {
                break;
            }
        }
        return cap;
    }

    public final void startWorkerTask() {
        Task task = new Task(true);
        task.initialize();
        Lock l = task.lock;
        l.lock();
        try {
            task.cap = this;
            runningTask = task;
            WorkerThread thread = new WorkerThread(task);
            thread.setTask();
            thread.start();
            task.id = thread.getId();
        } finally {
            l.unlock();
        }
    }

    public final boolean emptyRunQueue() {
        return runQueue.isEmpty();
    }

    public final boolean emptyThreadQueues() {
        // TODO: More optimal way of representing this?
        if (RtsFlags.ModeFlags.threaded) {
            return emptyRunQueue();
        } else {
            return emptyRunQueue() && emptyBlockedQueue() && emptySleepingQueue();
        }
    }
    public final void threadPaused(StgTSO tso) {
        maybePerformBlockedException(tso);
        if (tso.whatNext == ThreadKilled) return;
        ListIterator<StackFrame> sp = tso.sp;
        while (sp.hasNext()) {
            sp.next();
        }
        boolean stopLoop = false;
        boolean prevWasUpdateFrame = false;
        while (sp.hasPrevious() && !stopLoop) {
            StackFrame frame = sp.previous();
            MarkFrameResult result = frame.mark(this, tso);
            switch (result) {
                case Marked:
                    // do calculations
                case Stop:
                    stopLoop = true;
                    break;
                case Default:
                    // do calculations
                case UpdateEvaluted:
                    prevWasUpdateFrame = false;
                    break;
                case Update:
                    prevWasUpdateFrame = true;
                    break;
            }
        }
        // TODO: stack squeezing logic
    }

    public final boolean maybePerformBlockedException(StgTSO tso) {
        if (tso.whatNext == ThreadComplete /*|| tso.whatNext == ThreadFinished */) {
            if (tso.blockedExceptions.isEmpty()) {
                return false;
            } else {
                awakenBlockedExceptionQueue(tso);
                return true;
            }
        }

        if (!tso.blockedExceptions.isEmpty() && tso.hasFlag(TSO_BLOCKEX)) {
            // debugTraceCap(DEBUG_SCHED, this, "throwTo: thread %d has blocked exceptions but is inside block", tso.id);
        }

        if (!tso.blockedExceptions.isEmpty() && (!tso.hasFlag(TSO_BLOCKEX) || (tso.hasFlag(TSO_INTERRUPTIBLE) && tso.interruptible()))) {
            loop: do {
                MessageThrowTo msg = tso.blockedExceptions.peekFirst();
                if (msg == null) return false;
                msg.lock();
                tso.blockedExceptions.poll();
                if (!msg.isValid()) {
                    msg.unlock();
                    continue loop;
                }
                throwToSingleThreaded(msg.target, msg.exception);
                StgTSO source = msg.source;
                msg.done();
                tryWakeupThread(source);
                return true;
            } while (true);
        }
        return false;
    }

    public final void awakenBlockedExceptionQueue(StgTSO tso) {
        for (MessageThrowTo msg: tso.blockedExceptions) {
            msg.lock();
            if (msg.isValid()) {
                StgTSO source = msg.source;
                msg.done();
                tryWakeupThread(source);
            } else {
                msg.unlock();
            }
        }
        tso.blockedExceptions.clear();
    }

    public final void tryWakeupThread(StgTSO tso) {
        if (RtsFlags.ModeFlags.threaded) {
            if (tso.cap != this) {
                MessageWakeup msg = new MessageWakeup(tso);
                sendMessage(tso.cap, msg);
                // debugTraceCap
                return;
            }
        }

        switch (tso.whyBlocked) {
            case BlockedOnMVar:
            case BlockedOnMVarRead:
                if (!tso.inMVarOperation) {
                    tso.blockInfo = null;
                    break;
                } else {
                    return;
                }
            case BlockedOnMsgThrowTo:
                MessageThrowTo msg = (MessageThrowTo) tso.blockInfo;
                msg.lock();
                msg.unlock();
                if (msg.isValid()) {
                    return;
                } else {
                    // TODO: check stack operations
                    tso.sp.remove();
                }
                break;
            case BlockedOnBlackHole:
            case BlockedOnSTM:
            case ThreadMigrating:
                break;
            default:
                return;
        }
        tso.whyBlocked = NotBlocked;
        appendToRunQueue(tso);
        // can context switch now
    }

    public final void sendMessage(Capability target, Message msg) {
        // acquire target lock;
        Lock lock = target.lock;
        lock.lock();
        try {
            target.inbox.offerFirst(msg);
            if (target.runningTask == null) {
                target.runningTask = Task.myTask();
                target.release_(false);
            } else {
                target.interrupt();
            }
        } finally {
            lock.unlock();
        }
    }

    public final void release_(boolean alwaysWakeup) {
        Task task = runningTask;
        runningTask = null;
        Task workerTask = returningTasks.pollFirst();
        if (workerTask != null) {
            giveCapabilityToTask(workerTask);
            return;
        }
        /* TODO: Evaluate if this is required
        if (pendingSync != 0 && pendingSync != SYNC_GC_PAR) {
            lastFreeCapability = this;
            //debugTrace
            return;
            }*/
        StgTSO nextTSO = peekRunQueue();
        if (nextTSO.bound != null) {
            task = nextTSO.bound.task();
            giveCapabilityToTask(task);
            return;
        }

        if (spareWorkers.isEmpty()) {
            if (schedulerState.compare(SCHED_SHUTTING_DOWN) < 0 || !emptyRunQueue()) {
                //debugTrace
                startWorkerTask();
                return;
            }
        }

        if (alwaysWakeup || !emptyRunQueue() || !emptyInbox()
            || (!disabled && !emptySparkPool()) || globalWorkToDo()) {
            task = spareWorkers.poll();
            if (task != null) {
                giveCapabilityToTask(task);
                return;
            }
        }

        lastFreeCapability = this;
        //debugTrace;
    }

    public final void release() {
        lock.lock();
        try {
            release_(false);
        } finally {
            lock.unlock();
        }
    }

    public final void releaseAndWakeup() {
        lock.lock();
        try {
            release_(true);
        } finally {
            lock.unlock();
        }
    }

    public final void giveCapabilityToTask(Task task) {
        // debugTrace
        Lock lock = task.lock;
        lock.lock();
        try {
            if (!task.wakeup) {
                task.wakeup = true;
                //signalCondition
            }
        } finally {
            lock.unlock();
        }
    }

    public final boolean emptyInbox() {
        return inbox.isEmpty();
    }

    public final boolean emptySparkPool() {
        return sparks.isEmpty();
    }

    public final boolean globalWorkToDo() {
        return (schedulerState.compare(SCHED_INTERRUPTING) >= 0)
            || recentActivity == Inactive;
    }

    public final void interrupt() {
        stop();
        interrupt = true;
    }

    public final void contextSwitch() {
        stop();
        contextSwitch = true;
    }

    public final void stop() {
        // Find a method to force the stopping of a thread;
    }

    public final void throwToSingleThreaded(StgTSO tso, StgClosure exception) {
        throwToSingleThreaded__(tso, exception, false, null);
    }

    public final void throwToSingleThreaded_(StgTSO tso, StgClosure exception,
                                       boolean stopAtAtomically) {
        throwToSingleThreaded__(tso, exception, stopAtAtomically, null);
    }

    public final void throwToSingleThreaded__(StgTSO tso, StgClosure exception,
                                        boolean stopAtAtomically,
                                        StgUpdateFrame stopHere) {
        if (tso.whatNext == ThreadComplete || tso.whatNext == ThreadKilled) {
            return;
        }

        removeFromQueues(tso);
        raiseAsync(tso, exception, stopAtAtomically, stopHere);
    }

    public final void suspendComputation(StgTSO tso, StgUpdateFrame stopHere) {
        throwToSingleThreaded__(tso, null, false, stopHere);
    }

    public final void removeFromQueues(StgTSO tso) {
        switch (tso.whyBlocked) {
            case NotBlocked:
            case ThreadMigrating:
                return;
            case BlockedOnSTM:
                break;
            case BlockedOnMVar:
            case BlockedOnMVarRead:
                tso.removeFromMVarBlockedQueue();
                break;
            case BlockedOnBlackHole:
                break;
            case BlockedOnMsgThrowTo:
                MessageThrowTo m = (MessageThrowTo) tso.blockInfo;
                m.done();
                break;
            case BlockedOnRead:
            case BlockedOnWrite:
                // TODO: Remove the following check if this state never occurs
                // if the threaded rts
                if (RtsFlags.ModeFlags.threaded) {
                    blockedQueue.remove(tso);
                }
                break;
            case BlockedOnDelay:
                sleepingQueue.remove(tso);
                break;
            default:
                barf("removeFromQueues: %d", tso.whyBlocked);
        }
        tso.whyBlocked = NotBlocked;
        appendToRunQueue(tso);
    }

    public final void raiseAsync(StgTSO tso, StgClosure exception,
                           boolean stopAtAtomically, StgUpdateFrame stopHere) {
        ListIterator<StackFrame> sp = tso.sp;
        while (sp.hasNext()) { sp.next(); } // Ensure that sp is pointing to the top
        StgInd updatee = null;
        StackFrame frame = null;
        if (stopHere != null) {
            updatee = stopHere.updatee;
        }
        // Ensure stack is in proper format here;
        // This condition assumes that stopHere is below!
        while (stopHere == null || frame != stopHere) {
            //RaiseAsyncResult result = frame.doRaiseAsync(this, tso)
            // TODO: Implement later
        }

        if (tso.whyBlocked != NotBlocked) {
            tso.whyBlocked = NotBlocked;
            appendToRunQueue(tso);
        }
    }

    public final boolean messageBlackHole(MessageBlackHole msg) {
        StgInd bh = msg.bh;
        if (!bh.isEvaluated()) {
            boolean failure;
            do {
                failure = bh.indirectee.blackHole(this, msg);
            } while (failure);
            // TODO: Check this logic
            if (bh.isEvaluated()) {
                return false;
            } else {
                return true;
            }
        }
        return false;
    }

    public final void checkBlockingQueues(StgTSO tso) {
        for (StgBlockingQueue bq: tso.blockingQueues) {
            if (bq.bh.isEvaluated()) {
                wakeBlockingQueue(bq);
            }
        }
    }

    public final void updateThunk(StgTSO tso, StgInd thunk, StgClosure val) {
        if (thunk.isEvaluated()) {
            thunk.updateWithIndirection(val);
        } else {
            StgClosure v = thunk.indirectee;
            thunk.updateWithIndirection(val);
            v.thunkUpdate(this, tso);
        }
    }

    public final void wakeBlockingQueue(StgBlockingQueue blockingQueue) {
        for (MessageBlackHole msg: blockingQueue) {
            if (msg.isValid())
                tryWakeupThread(msg.tso);
        }
        blockingQueue.clear();
        // do cleanup here to destroy the BQ;
    }

    public final SchedulerStatus getSchedStatus() {
        return runningTask.incall.returnStatus;
    }

    public final static Capability getFreeCapability() {
        if (lastFreeCapability.runningTask != null) {
            for (Capability cap: capabilities) {
                if (cap.runningTask == null) {
                    return cap;
                }
            }
            return lastFreeCapability;
        } else {
            return lastFreeCapability;
        }
    }

    public final void newReturningTask(Task task) {
        returningTasks.addLast(task);
    }

    public final void popReturningTask() {
        returningTasks.pollFirst();
    }

    public final void giveToTask(Task task) {
        //debugTrace
        Lock l = task.lock;
        l.lock();
        try {
            if (!task.wakeup) {
                task.wakeup = true;
                task.condition.signalAll();
            }
        } finally {
            l.unlock();
        }
    }

    public final Task suspendThread(boolean interruptible) {
        Task task = runningTask;
        StgTSO tso = context.currentTSO;
        tso.whatNext = ThreadRunGHC;
        threadPaused(tso);
        if (interruptible) {
            tso.whyBlocked = BlockedOnJavaCall_Interruptible;
        } else {
            tso.whyBlocked = BlockedOnJavaCall;
        }
        Task.InCall incall = task.incall;
        incall.suspendedTso = tso;
        incall.suspendedCap = this;
        context.currentTSO = null;
        lock.lock();
        try {
            suspendTask(task);
            inHaskell = false;
            release_(false);
        } finally {
            lock.unlock();
        }
        return task;
    }

    public final static Capability resumeThread(Task task) {
        Task.InCall incall = task.incall;
        Capability cap = incall.suspendedCap;
        task.cap = cap;
        cap = task.waitForCapability(cap);
        cap.recoverSuspendedTask(task);
        StgTSO tso = incall.suspendedTso;
        incall.suspendedTso = null;
        incall.suspendedCap = null;
        // remove the tso _link
        tso.whyBlocked = NotBlocked;
        if (tso.hasFlag(TSO_BLOCKEX)) {
            if (!tso.blockedExceptions.isEmpty()) {
                cap.maybePerformBlockedException(tso);
            }
        }
        cap.context.currentTSO = tso;
        cap.inHaskell = true;
        return cap;
    }

    public final void suspendTask(Task task) {
        // TODO: Should the incall be removed from any existing data structures?
        suspendedJavaCalls.push(task.incall);
    }

    public final void recoverSuspendedTask(Task task) {
        suspendedJavaCalls.remove(task.incall);
    }

    public final void scheduleWorker(Task task) {
        Capability cap = schedule(task);
        Lock l = cap.lock;
        l.lock();
        try {
            cap.release_(false);
            task.workerTaskStop();
        } finally {
            l.unlock();
        }
    }
    public final boolean singletonRunQueue() {
        return (runQueue.size() == 1);
    }

    public final boolean tryGrab(Task task) {
        if (runningTask != null) return false;
        lock.lock();
        try {
            if (runningTask != null) {
                lock.unlock();
                return false;
            } else {
                task.cap = this;
                runningTask = task;
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    public final void enqueueWorker() {
        Task task = runningTask;
        boolean taken = spareWorkers.offer(task);
        if (!taken) {
            release_(false);
            task.workerTaskStop();
            lock.unlock();
            Task.shutdownThread();
        }
    }

    public final void gcWorkerThread() {
        // TODO: Implement
    }

    public static void contextSwitchAll() {
        for (Capability c: capabilities) {
            c.contextSwitch();
        }
    }

    public static void interruptAll() {
        for (Capability c: capabilities) {
            c.interrupt();
        }
    }

    public final boolean handleThreadFinished(Task task, StgTSO t) {
        awakenBlockedExceptionQueue(t);

        if (t.bound != null) {
            if (t.bound != task.incall) {
                // should only happen in THREADED_RTS
                appendToRunQueue(t);
                return false;
            }

            if (t.whatNext == ThreadComplete) {
                if (task.incall.ret != null) {
                    StgEnter enterFrame = (StgEnter) task.incall.tso.stack.peek();
                    task.incall.ret = enterFrame.closure;
                }
                task.incall.returnStatus = Success;
            } else {
                if (task.incall.ret != null) {
                    task.incall.ret = null;
                }
                if (schedulerState.compare(SCHED_INTERRUPTING) >= 0) {
                    if (false /* HeapOverflow */) {
                        task.incall.returnStatus = HeapExhausted;
                    } else{
                        task.incall.returnStatus = Interrupted;
                    }
                } else {
                    task.incall.returnStatus = Killed;
                }
            }
            t.bound = null;
            task.incall.tso = null;
            return true;
        }

        return false;
    }

    public final boolean handleYield(StgTSO t, WhatNext prevWhatNext) {
        if (!contextSwitch && t.whatNext != prevWhatNext) {
            return true;
        }

        if (contextSwitch) {
            contextSwitch = false;
            appendToRunQueue(t);
        } else {
            pushOnRunQueue(t);
        }

        return false;
    }

    public final void threadStackOverflow(StgTSO tso) {
        // TODO: Do stack related stuff here
    }

    public final boolean handleHeapOverflow(StgTSO tso) {
        // TODO: Do GC-related stuff here
        return false;
    }

    public final HaskellResult ioManagerStart() {
        return Rts.evalIO(this, null /* base_GHCziConcziIO_ensureIOManagerIsRunning_closure */);
    }

    public final void postRunThread(StgTSO t) {
        if (/*t.trec != NO_TREC && */ false && t.whyBlocked == NotBlocked) {
            /* TODO: Implement when implementing STM */
        }
    }

    public final void promoteInRunQueue(StgTSO tso) {
        removeFromRunQueue(tso);
        pushOnRunQueue(tso);
    }

    public final void removeFromRunQueue(StgTSO tso) {
        runQueue.remove(tso);
    }

    public final boolean throwToMsg(MessageThrowTo msg) {
        StgTSO target = msg.target;
        // retry: write_barrier
        retry: do {
            if (target.whatNext != ThreadComplete
                && target.whatNext != ThreadKilled) {
                Capability targetCap = target.cap;
                if (target.cap != this) {
                    sendMessage(targetCap, msg);
                } else {
                    WhyBlocked status = target.whyBlocked;
                    switch (status) {
                        case NotBlocked:
                            if (!target.hasFlag(TSO_BLOCKEX)) {
                                raiseAsync(target, msg.exception, false, null);
                            } else {
                                target.blockedExceptions.offer(msg);
                                return false;
                            }
                            break;
                        case BlockedOnMsgThrowTo:
                            MessageThrowTo m = (MessageThrowTo) target.blockInfo;
                            if (m.id < msg.id) {
                                m.lock();
                            } else {
                                if (!m.tryLock()) {
                                    sendMessage(targetCap, msg);
                                    return false;
                                }
                            }
                            if (!msg.isValid()) {
                                m.unlock();
                                tryWakeupThread(target);
                                continue retry;
                            }
                            //TODO: Extra if check?
                            if (target.hasFlag(TSO_BLOCKEX)
                                && !target.hasFlag(TSO_INTERRUPTIBLE)) {
                                m.unlock();
                                target.blockedExceptions.offer(msg);
                                return false;
                            }
                            m.done();
                            raiseAsync(target, msg.exception, false, null);
                            break;
                        case BlockedOnMVar:
                        case BlockedOnMVarRead:
                            StgMVar mvar = (StgMVar) target.blockInfo;
                            // if not mvar retry
                            mvar.lock();
                            if ((target.whyBlocked != BlockedOnMVar &&
                                target.whyBlocked != BlockedOnMVarRead)
                                || target.blockInfo != mvar) {
                                mvar.unlock();
                                continue retry;
                            } else if (!target.inMVarOperation) {
                                mvar.unlock();
                                tryWakeupThread(target);
                                continue retry;
                            } else if (target.hasFlag(TSO_BLOCKEX)
                                    && !target.hasFlag(TSO_INTERRUPTIBLE)) {
                                target.blockedExceptions.offer(msg);
                                mvar.unlock();
                                return false;
                            } else {
                                target.removeFromMVarBlockedQueue();
                                raiseAsync(target, msg.exception, false, null);
                                mvar.unlock();
                            }
                            break;
                        case BlockedOnBlackHole:
                            if (target.hasFlag(TSO_BLOCKEX)) {
                                target.blockedExceptions.offer(msg);
                                return false;
                            } else {
                                ((MessageBlackHole)target.blockInfo).invalidate();
                                raiseAsync(target, msg.exception, false, null);
                            }
                            break;
                        case BlockedOnSTM:
                            target.lock();
                            if (target.whyBlocked != BlockedOnSTM) {
                                target.unlock();
                                continue retry;
                            } else if (target.hasFlag(TSO_BLOCKEX) &&
                                    !target.hasFlag(TSO_INTERRUPTIBLE)) {
                                target.blockedExceptions.offer(msg);
                                target.unlock();
                                return false;
                            } else {
                                raiseAsync(target, msg.exception, false, null);
                                target.unlock();
                            }
                            break;
                        case BlockedOnJavaCall_Interruptible:
                            // check for THREADED_RTS
                            Task task = null;
                            for (Task.InCall incall: suspendedJavaCalls) {
                                if (incall.suspendedTso == target) {
                                    task = incall.task();
                                    break;
                                }
                            }
                            if (task != null) {
                                target.blockedExceptions.offer(msg);
                                if (!(target.hasFlag(TSO_BLOCKEX) &&
                                    !target.hasFlag(TSO_INTERRUPTIBLE))) {
                                    task.interruptWorker();
                                }
                                return false;
                            } else {
                                //debug output
                            }
                        case BlockedOnJavaCall:
                            target.blockedExceptions.offer(msg);
                            return false;
                        case BlockedOnRead:
                        case BlockedOnWrite:
                        case BlockedOnDelay:
                            if (target.hasFlag(TSO_BLOCKEX) &&
                                !target.hasFlag(TSO_INTERRUPTIBLE)) {
                                target.blockedExceptions.offer(msg);
                                return false;
                            } else {
                                removeFromQueues(target);
                                raiseAsync(target, msg.exception, false, null);
                            }
                            break;
                        case ThreadMigrating:
                            tryWakeupThread(target);
                            continue retry;
                        default:
                            barf("throwTo: unrecognized why_blocked (%p)", target.whyBlocked);
                    }
                }
            }
            return true;
        } while (true);
    }

    /* STM Operations */
    public final StgClosure stmReadTvar(Stack<StgTRecHeader> trecStack, StgTVar tvar) {
        StgClosure result;
        StgTRecHeader trec = trecStack.peek();
        EntrySearchResult searchResult = STM.getEntry(trecStack, tvar);
        StgTRecHeader entryIn = searchResult.header;
        TRecEntry entry = searchResult.entry;
        if (entry == null) {
            if (entryIn != trec) {
                TRecEntry newEntry = getNewEntry(trec);
                newEntry.tvar = tvar;
                newEntry.expectedValue = entry.expectedValue;
                newEntry.newValue = entry.newValue;
            }
            result = entry.newValue;
        } else {
            StgClosure currentValue = STM.readCurrentValue(trec, tvar);
            TRecEntry newEntry = getNewEntry(trec);
            newEntry.tvar = tvar;
            newEntry.expectedValue = currentValue;
            newEntry.newValue = currentValue;
            result = currentValue;
        }
        return result;
    }

    public final TRecEntry getNewEntry(StgTRecHeader t) {
        StgTRecChunk c = t.chunkStack.peek();
        TRecEntry entry = new TRecEntry();
        if (c.entries.size() <= TREC_CHUNK_NUM_ENTRIES) {
            c.entries.add(entry);
        } else {
            c = getNewTRecChunk();
            c.entries.add(entry);
            t.chunkStack.push(c);
        }
        return entry;
    }

    public final StgTRecChunk getNewTRecChunk() {
        StgTRecChunk result = null;
        if (freeTrecChunks.isEmpty()) {
            result = new StgTRecChunk();
        } else {
            result = freeTrecChunks.pop();
            result.reset();
        }
        return result;
    }

    public final void stmWriteTvar(Stack<StgTRecHeader> trecStack, StgTVar tvar, StgClosure newValue) {
        StgTRecHeader trec = trecStack.peek();
        EntrySearchResult searchResult = STM.getEntry(trecStack, tvar);
        StgTRecHeader entryIn = searchResult.header;
        TRecEntry entry = searchResult.entry;
        if (entry == null) {
            if (entryIn == trec) {
                entry.newValue = newValue;
            }else {
                TRecEntry newEntry = getNewEntry(trec);
                newEntry.tvar = tvar;
                newEntry.expectedValue = entry.expectedValue;
                newEntry.newValue = newValue;
            }
        } else {
            StgClosure currentValue = STM.readCurrentValue(trec, tvar);
            TRecEntry newEntry = getNewEntry(trec);
            newEntry.tvar = tvar;
            newEntry.expectedValue = currentValue;
            newEntry.newValue = newValue;
        }
    }
}
