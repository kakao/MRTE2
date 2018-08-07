package mrte.util;

import java.io.PrintStream;


public class HexHelper {

	public static void dumpBytes(PrintStream outputStream, byte[] bytes){
		int width = 16;
		for (int index = 0; index < bytes.length; index += width) {
			try{
				printHex(outputStream, bytes, index, width);
				printAscii(outputStream, bytes, index, width);
			}catch(Exception ignore){
				outputStream.println("Can't dump byte[], " + ignore.getMessage());
			}
		}
	}

	private static void printHex(PrintStream outputStream, byte[] bytes, int offset, int width) {
		for (int index = 0; index < width; index++) {
			if (index + offset < bytes.length) {
				outputStream.printf("%02x ", bytes[index + offset]);
			} else {
				outputStream.print("	");
			}
		}
	}

	private static void printAscii(PrintStream outputStream, byte[] bytes, int index, int width) throws Exception{
        // Write a character for each byte in the printable ascii range.
		outputStream.print("| ");
		width = Math.min(width, bytes.length - index);
        for (int i = index; i < index+width; i++) {
            if (bytes[i] > 31 && bytes[i] < 127) {
            	outputStream.print((char)bytes[i]);
            } else {
            	outputStream.print(".");
            }
        }
        outputStream.println();
	}
	
//	public static byte[] hexStringToByteArray(String s) {
//		byte[] b = new byte[s.length() / 2];
//		for (int i = 0; i < b.length; i++) {
//			int index = i * 2;
//			int v = Integer.parseInt(s.substring(index, index + 2), 16);
//			b[i] = (byte) v;
//		}
//		return b;
//	}
	
	public static byte[] hexStringToByteArray(String s) {
	    int len = s.length();
	    byte[] data = new byte[len / 2];
	    for (int i = 0; i < len; i += 2) {
	        data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
	                             + Character.digit(s.charAt(i+1), 16));
	    }
	    return data;
	}
}