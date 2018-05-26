package krpc.trace;

public class Event {

	String type;
	String action;
	String status;
	String data;
	
	long startMicros = System.nanoTime()/1000;
	
	public Event(String type,String action,String status,String data) {
		this.type = type;
		this.action = action;
		this.status = status;
		this.data = data;
	}

	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getData() {
		return data;
	}
	public void setData(String data) {
		this.data = data;
	}

	public String getAction() {
		return action;
	}
	public void setAction(String action) {
		this.action = action;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public long getStartMicros() {
		return startMicros;
	}

}
