package com.netease.backend.coordinator.processor;

import java.util.Iterator;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import com.netease.backend.coordinator.task.TxResult;
import com.netease.backend.coordinator.task.TxResultWatcher;
import com.netease.backend.coordinator.transaction.Transaction;
import com.netease.backend.coordinator.transaction.TxManager;
import com.netease.backend.coordinator.transaction.TxTable;
import com.netease.backend.tcc.error.CoordinatorException;
import com.netease.backend.tcc.error.HeuristicsException;

public class RetryProcessor implements Runnable {
	
	private Logger logger = Logger.getLogger("RetryProcessor");
	
	private Task spots[];
	private TxResultWatcher watchers[];
	private TxManager txManager = null;
	private TxTable txTable = null;
	private DelayQueue<Task> retryQueue = new DelayQueue<Task>();
	private Lock lock = new ReentrantLock();
	private Condition isSpotFree = lock.newCondition();
	private volatile boolean stop = false;
	
	public RetryProcessor(int parallelism) {
		this.spots = new Task[parallelism];
		this.watchers = new ResultWatcher[parallelism];
		for (int i = 0; i < parallelism; i++)
			watchers[i] = new ResultWatcher(i);
	}

	@Override
	public void run() {
		while (!stop && !Thread.interrupted()) {
			lock.lock();
			for (int i = 0; i < spots.length; i++) {
				if (spots[i] == null) {
					try {
						spots[i] = retryQueue.take();
						try {
							spots[i].execute(watchers[i]);
						} catch (Exception e) {
							if (spots[i].delay(10000))
								retryQueue.offer(spots[i]);
							else
								logger.error(spots[i].getFailedDescrip());
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					} 
				}
			}
			isSpotFree.awaitUninterruptibly();
			lock.unlock();
		}
	}
	
	public void recover() {
		int confirmCount = 0;
		int cancelCount = 0;
		int expireCount = 0;
		for (Iterator<Transaction> it = txTable.getTxMap().values().iterator(); it.hasNext(); ) {
			Transaction tx = it.next();
			switch (tx.getAction()) {
				case CANCEL:
					cancelCount++;
					process(tx, 1);
					break;
				case CONFIRM:
					confirmCount++;
					process(tx, 1);
					break;
				case EXPIRE:
					expireCount++;
					process(tx, 1);
					break;
				default:
					break;
			}
		}
		StringBuilder builder = new StringBuilder();
		builder.append("Init retrying tasks,confirm:").append(confirmCount);
		builder.append(",cancel:" + cancelCount);
		builder.append(",expire:" + expireCount);
		logger.info(builder);
		int count = 0;
		while (retryQueue.size() != 0) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if (count % 5 == 0)
				logger.info("retry queue left Task count:" + retryQueue.size());
		}
	}
	
	/*
	 * failed or success, just drop it
	 */
	private void processResult(int index, TxResult result) {
		spots[index] = null;
		txTable.remove(result.getUUID());
		lock.lock();
		isSpotFree.signalAll();
		lock.unlock();
	}
	
	public void process(Transaction tx, int times) {
		retryQueue.offer(new Task(tx, times));
	}
	
	private class ResultWatcher implements TxResultWatcher {
		
		private int index;
		
		public ResultWatcher(int index) {
			this.index = index;
		}

		@Override
		public void notifyResult(TxResult result) {
			processResult(index, result);
		}
	}
	
	private class Task implements Delayed {
		
		private Transaction tx;
		private long ts;
		private int times = 1;
		
		Task(Transaction tx, int times) {
			this.tx = tx;
			this.ts = System.currentTimeMillis();
			this.times = times;
		}

		public void execute(TxResultWatcher watcher) throws HeuristicsException, CoordinatorException {
			times--;
			txManager.retryAsync(tx, watcher);
		}

		@Override
		public int compareTo(Delayed o) {
			Task task = (Task) o;
			return (int) (ts - task.ts);
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(ts, TimeUnit.MILLISECONDS);
		}
		
		public boolean delay(long ts) {
			if (times <= 0)
				return false;
			times--;
			this.ts = ts;
			return true;
		}
		
		public String getFailedDescrip() {
			StringBuilder builder = new StringBuilder();
			builder.append("Retry ").append(tx.getAction().name())
				.append(" failed, uuid:").append(tx.getUUID());
			return builder.toString();
		}
	}
}
