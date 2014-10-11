package com.netease.backend.coordinator.transaction;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.netease.backend.tcc.Procedure;

public class Transaction {

	private long uuid = -1;
	private long createTime = -1;
	private long beginTime = -1;
	private long endTime = -1;
	private AtomicReference<Action> status = new AtomicReference<Action>();
	protected List<Procedure> expireList = null;
	protected List<Procedure> procList = null;
	
	public Transaction(long uuid, List<Procedure> expireList) {
		this.uuid = uuid;
		this.expireList = expireList;
		this.createTime = System.currentTimeMillis();
		status.set(Action.REGISTERED);
	}
	
	public long getUUID() {
		return uuid;
	}
	
	public long getBeginTime() {
		return beginTime;
	}

	public void setBeginTime(long beginTime) {
		this.beginTime = beginTime;
	}

	public long getEndTime() {
		return endTime;
	}

	public void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	public List<Procedure> getExpireList() {
		return expireList;
	}

	public List<Procedure> getConfirmList() {
		return procList;
	}
	
	public List<Procedure> getCancelList() {
		return procList;
	}

	public long getCreateTime() {
		return createTime;
	}

	public void setCreateTime(long createTime) {
		this.createTime = createTime;
	}
	
	public Action getAction() {
		return status.get();
	}
	
	public void confirm(List<Procedure> procList) throws IllegalActionException {
		if (status.compareAndSet(Action.REGISTERED, Action.CONFIRM))
			this.procList = procList;
		else 
			throw new IllegalActionException(uuid, status.get(), Action.CONFIRM);
	}
	
	public void cancel(List<Procedure> procList) throws IllegalActionException {
		if (status.compareAndSet(Action.REGISTERED, Action.CANCEL))
			this.procList = procList;
		else 
			throw new IllegalActionException(uuid, status.get(), Action.CANCEL);
	}
	
	public void expire() throws IllegalActionException {
		if (!status.compareAndSet(Action.REGISTERED, Action.EXPIRED))
			throw new IllegalActionException(uuid, status.get(), Action.EXPIRED);
	}
	
	public long getLastTimeStamp() {
		return endTime == -1 ? (beginTime == -1 ? createTime : beginTime) : endTime;
	}
}
