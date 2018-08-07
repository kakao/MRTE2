package mrte.util;


public class ByteReadException extends Exception{
	public int totalDataLength;
	public int startPoint;
	public int needLength;
	
	public ByteReadException(String funcName, int startPoint, int needLength){
		super(funcName + " : need "+(startPoint + needLength)+" more bytes, but given byte[] is null");
		
		this.totalDataLength = 0;
		this.startPoint = startPoint;
		this.needLength = needLength;
	}
	
	public ByteReadException(String funcName, byte[] data, int startPoint, int needLength){
		super(funcName + " : need "+(startPoint + needLength)+" more bytes, but given byte[] has only " + data.length + " bytes");
		
		this.totalDataLength = data.length;
		this.startPoint = startPoint;
		this.needLength = needLength;
	}
}
