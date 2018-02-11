package nachos.threads;

import nachos.machine.*;
import java.util.LinkedList;

/**
 * A scheduler that chooses threads based on their priorities.
 * 
 * <p>
 * A priority scheduler associates a priority with each thread. The next thread
 * to be dequeued is always a thread with priority no less than any other
 * waiting thread's priority. Like a round-robin scheduler, the thread that is
 * dequeued is, among all the threads of the same (highest) priority, the thread
 * that has been waiting longest.
 * 
 * <p>
 * Essentially, a priority scheduler gives access in a round-robin fassion to
 * all the highest-priority threads, and ignores all other threads. This has the
 * potential to starve a thread if there's always a thread waiting with higher
 * priority.
 * 
 * <p>
 * A priority scheduler must partially solve the priority inversion problem; in
 * particular, priority must be donated through locks, and through joins.
 */
public class PriorityScheduler extends Scheduler {
	/**
	 * Allocate a new priority scheduler.
	 */
	public PriorityScheduler() {
	}

	/**
	 * Allocate a new priority thread queue.
	 * 
	 * @param transferPriority <tt>true</tt> if this queue should transfer
	 * priority from waiting threads to the owning thread.
	 * @return a new priority thread queue.
	 */
	public ThreadQueue newThreadQueue(boolean transferPriority) {
		return new PriorityQueue(transferPriority);
	}

	public int getPriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getPriority();
	}

	public int getEffectivePriority(KThread thread) {
		Lib.assertTrue(Machine.interrupt().disabled());

		return getThreadState(thread).getEffectivePriority();
	}

	public void setPriority(KThread thread, int priority) {
		Lib.assertTrue(Machine.interrupt().disabled());

		Lib.assertTrue(priority >= priorityMinimum
				&& priority <= priorityMaximum);

		getThreadState(thread).setPriority(priority);
	}

	public boolean increasePriority() {
		boolean intStatus = Machine.interrupt().disable();
		boolean ret = true;

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMaximum)
			ret = false;
		else
			setPriority(thread, priority + 1);

		Machine.interrupt().restore(intStatus);
		return ret;
	}

	public boolean decreasePriority() {
		boolean intStatus = Machine.interrupt().disable();
		boolean ret = true;

		KThread thread = KThread.currentThread();

		int priority = getPriority(thread);
		if (priority == priorityMinimum)
			ret = false;
		else
			setPriority(thread, priority - 1);

		Machine.interrupt().restore(intStatus);
		return ret;
	}

	/**
	 * The default priority for a new thread. Do not change this value.
	 */
	public static final int priorityDefault = 1;

	/**
	 * The minimum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMinimum = 0;

	/**
	 * The maximum priority that a thread can have. Do not change this value.
	 */
	public static final int priorityMaximum = 7;

	/**
	 * Return the scheduling state of the specified thread.
	 * 
	 * @param thread the thread whose scheduling state to return.
	 * @return the scheduling state of the specified thread.
	 */
	protected ThreadState getThreadState(KThread thread) {
		if (thread.schedulingState == null)
			thread.schedulingState = new ThreadState(thread);

		return (ThreadState) thread.schedulingState;
	}

	/**
	 * A <tt>ThreadQueue</tt> that sorts threads by priority.
	 */
	protected class PriorityQueue extends ThreadQueue {
		
		PriorityQueue(boolean transferPriority) {
			this.transferPriority = transferPriority;
		}

		public void waitForAccess(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).waitForAccess(this);
		}

		public void acquire(KThread thread) {
			Lib.assertTrue(Machine.interrupt().disabled());
			getThreadState(thread).acquire(this);
		}

		public KThread nextThread() {
			Lib.assertTrue(Machine.interrupt().disabled());
			// implement me
			if (this.wQueue.isEmpty()) 
				return null;
			
			if (this.threadOwner != null && this.transferPriority)
				this.threadOwner.holdingQueues.remove(this);
			
			ThreadState nextThreadState = pickNextThread();
			
			if (nextThreadState != null) {
				this.wQueue.remove(nextThreadState);
				nextThreadState.acquire(this);
				return nextThreadState.thread;
			}
			else
				return null;
			
		}
		
		public void setNeedToUpdate() {
			if(!this.transferPriority)
				return;
			this.needToUpdate = true;
			if (this.threadOwner != null)
				threadOwner.setNeedToUpdate();
		}
		
		public int getEffectivePqPriority() {
			if (this.transferPriority == false)
				return priorityMinimum;
			
			if (this.needToUpdate) {

				this.effectivePriority = priorityMinimum;

				for (ThreadState t : wQueue){
					this.effectivePriority = Math.max(this.effectivePriority, t.getEffectivePriority());
				}

				this.needToUpdate = false;
			}

			return this.effectivePriority;
		}

		/**
		 * Return the next thread that <tt>nextThread()</tt> would return,
		 * without modifying the state of this queue.
		 * 
		 * @return the next thread that <tt>nextThread()</tt> would return.
		 */
		protected ThreadState pickNextThread() {
			// implement me
			ThreadState nextThread = null;
			int priority = priorityMinimum;
			
			for (ThreadState t : this.wQueue) {
				int tmpPriority = this.getEffectivePqPriority();
				if (priority < tmpPriority) {
					priority = tmpPriority;
					nextThread = t;
				}
			}
			return nextThread;
		}

		public void print() {
			Lib.assertTrue(Machine.interrupt().disabled());
			// implement me (if you want)
		}

		/**
		 * <tt>true</tt> if this queue should transfer priority from waiting
		 * threads to the owning thread.
		 */
		public boolean transferPriority;
		private LinkedList<ThreadState> wQueue = new LinkedList<ThreadState>();
		private ThreadState threadOwner;
		private boolean needToUpdate;
		private int effectivePriority;
	}

	/**
	 * The scheduling state of a thread. This should include the thread's
	 * priority, its effective priority, any objects it owns, and the queue it's
	 * waiting for, if any.
	 * 
	 * @see nachos.threads.KThread#schedulingState
	 */
	protected class ThreadState {
		/**
		 * Allocate a new <tt>ThreadState</tt> object and associate it with the
		 * specified thread.
		 * 
		 * @param thread the thread this state belongs to.
		 */
		public ThreadState(KThread thread) {
			this.thread = thread;
			this.holdingQueues = new LinkedList<PriorityQueue>();
			this.needToUpdate = false;
			this.effectivePriority = priorityDefault;
			this.waitingPQ = null;
			setPriority(priorityDefault);
		}

		/**
		 * Return the priority of the associated thread.
		 * 
		 * @return the priority of the associated thread.
		 */
		public int getPriority() {
			return priority;
		}

		/**
		 * Return the effective priority of the associated thread.
		 * 
		 * @return the effective priority of the associated thread.
		 */
		public int getEffectivePriority() {
			// implement me
			if (this.needToUpdate) {
				this.effectivePriority = this.priority;
				for (PriorityQueue p : holdingQueues) {
					this.effectivePriority = Math.max(this.effectivePriority, p.getEffectivePqPriority());	
				}
				this.needToUpdate = false;
			}
			return this.effectivePriority;
		}
		
		public void setNeedToUpdate() {
			this.needToUpdate = true;
			if (waitingPQ != null)
				waitingPQ.setNeedToUpdate();
		}

		/**
		 * Set the priority of the associated thread to the specified value.
		 * 
		 * @param priority the new priority.
		 */
		public void setPriority(int priority) {
			if (this.priority == priority)
				return;

			this.priority = priority;

			// implement me
			setNeedToUpdate();

		}

		/**
		 * Called when <tt>waitForAccess(thread)</tt> (where <tt>thread</tt> is
		 * the associated thread) is invoked on the specified priority queue.
		 * The associated thread is therefore waiting for access to the resource
		 * guarded by <tt>waitQueue</tt>. This method is only called if the
		 * associated thread cannot immediately obtain access.
		 * 
		 * @param waitQueue the queue that the associated thread is now waiting
		 * on.
		 * 
		 * @see nachos.threads.ThreadQueue#waitForAccess
		 */
		public void waitForAccess(PriorityQueue waitQueue) {
			// implement me
			Lib.assertTrue(Machine.interrupt().disabled());
			
			if (holdingQueues.contains(waitQueue)) {
				waitQueue.threadOwner = null;
				holdingQueues.remove(waitQueue);
			}
			this.waitingPQ = waitQueue;
			waitQueue.wQueue.add(this);
			waitQueue.setNeedToUpdate();
		}

		/**
		 * Called when the associated thread has acquired access to whatever is
		 * guarded by <tt>waitQueue</tt>. This can occur either as a result of
		 * <tt>acquire(thread)</tt> being invoked on <tt>waitQueue</tt> (where
		 * <tt>thread</tt> is the associated thread), or as a result of
		 * <tt>nextThread()</tt> being invoked on <tt>waitQueue</tt>.
		 * 
		 * @see nachos.threads.ThreadQueue#acquire
		 * @see nachos.threads.ThreadQueue#nextThread
		 */
		public void acquire(PriorityQueue waitQueue) {
			// implement me
			waitQueue.threadOwner = this;
			this.holdingQueues.add(waitQueue);
			if (this.waitingPQ == waitQueue)
				this.waitingPQ = null;
			
			waitQueue.setNeedToUpdate();
			setNeedToUpdate();
		}

		/** The thread with which this object is associated. */
		protected KThread thread;

		/** The priority of the associated thread. */
		protected int priority;
		protected int effectivePriority;
		protected LinkedList<PriorityQueue> holdingQueues;
		protected boolean needToUpdate;
		protected PriorityQueue waitingPQ;
	}
	
	
	public static void selfTest() {
		System.out.println("---------PriorityScheduler test---------------------");
		PriorityScheduler s = new PriorityScheduler();
		ThreadQueue queue = s.newThreadQueue(true);
		ThreadQueue queue2 = s.newThreadQueue(true);
		ThreadQueue queue3 = s.newThreadQueue(true);
		ThreadQueue queue4 = s.newThreadQueue(true);
		
		KThread thread1 = new KThread();
		KThread thread2 = new KThread();
		KThread thread3 = new KThread();
		KThread thread4 = new KThread();
		KThread thread5 = new KThread();
		
		KThread thread11 = new KThread();
		KThread thread12 = new KThread();
		KThread thread13 = new KThread();
		KThread thread14 = new KThread();
		
		
		thread1.setName("thread1");
		thread2.setName("thread2");
		thread3.setName("thread3");
		thread4.setName("thread4");
		thread5.setName("thread5");

		
		thread11.setName("thread11");
		thread12.setName("thread12");
		thread13.setName("thread13");
		thread14.setName("thread14");

		
		boolean intStatus = Machine.interrupt().disable();
		
		queue4.acquire(thread11);
		queue4.waitForAccess(thread12);
		queue4.waitForAccess(thread13);
		queue4.waitForAccess(thread14);
		
		s.getThreadState(thread11).setPriority(1);
		s.getThreadState(thread12).setPriority(2);
		s.getThreadState(thread13).setPriority(3);
		s.getThreadState(thread14).setPriority(4);
		
		System.out.println("thread11 EP="+s.getThreadState(thread11).getEffectivePriority());
		System.out.println("thread12 EP="+s.getThreadState(thread12).getEffectivePriority());
		System.out.println("thread13 EP="+s.getThreadState(thread13).getEffectivePriority());
		System.out.println("thread14 EP="+s.getThreadState(thread14).getEffectivePriority());
	
		queue3.acquire(thread1);
		queue.acquire(thread1);
		queue.waitForAccess(thread2);
		queue2.acquire(thread4);
		queue2.waitForAccess(thread1);
		System.out.println("thread1 EP="+s.getThreadState(thread1).getEffectivePriority());
		System.out.println("thread2 EP="+s.getThreadState(thread2).getEffectivePriority());
		System.out.println("thread4 EP="+s.getThreadState(thread4).getEffectivePriority());
		
		s.getThreadState(thread2).setPriority(3);
		
		System.out.println("After setting thread2's EP=3:");
		System.out.println("thread1 EP="+s.getThreadState(thread1).getEffectivePriority());
		System.out.println("thread2 EP="+s.getThreadState(thread2).getEffectivePriority());
		System.out.println("thread4 EP="+s.getThreadState(thread4).getEffectivePriority());
		
		queue.waitForAccess(thread3);
		s.getThreadState(thread3).setPriority(5);
		
		System.out.println("After adding thread3 with EP=5:");
		System.out.println("thread1 EP="+s.getThreadState(thread1).getEffectivePriority());
		System.out.println("thread2 EP="+s.getThreadState(thread2).getEffectivePriority());
		System.out.println("thread3 EP="+s.getThreadState(thread3).getEffectivePriority());
		System.out.println("thread4 EP="+s.getThreadState(thread4).getEffectivePriority());
		
		s.getThreadState(thread3).setPriority(2);
		
		System.out.println("After setting thread3 EP=2:");
		System.out.println("thread1 EP="+s.getThreadState(thread1).getEffectivePriority());
		System.out.println("thread2 EP="+s.getThreadState(thread2).getEffectivePriority());
		System.out.println("thread3 EP="+s.getThreadState(thread3).getEffectivePriority());
		System.out.println("thread4 EP="+s.getThreadState(thread4).getEffectivePriority());
		
		System.out.println("Thread1 acquires queue and queue3");
		
		Machine.interrupt().restore(intStatus);
		System.out.println("--------End PriorityScheduler test------------------");
	}
	
	
	
}


























