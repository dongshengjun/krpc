package krpc.rpc.impl.transport;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import krpc.rpc.core.ReflectionUtils;
import krpc.rpc.core.RetCodes;
import krpc.rpc.core.RpcCodec;
import krpc.rpc.core.RpcData;
import krpc.rpc.core.RpcException;
import krpc.rpc.core.ServiceMetas;
import krpc.rpc.core.proto.RpcMeta;
import krpc.rpc.util.ZipUnzip;
import krpc.rpc.util.ZlibTool;

public class DefaultRpcCodec implements RpcCodec {

	static Logger log = LoggerFactory.getLogger(DefaultRpcCodec.class);

	static final int GZIP = 1;
	static final int SNAPPY = 2;
	
	ServiceMetas serviceMetas;

	private byte[] reqHeartBeatBytes;
	private byte[] resHeartBeatBytes;
	
	private ZipUnzip zlibTool;
	private ZipUnzip snappyTool;
	
	private HashMap<Integer,Integer> zipMap = new HashMap<>(); // only for encode
	private HashMap<Integer,Integer> minSizeToZipMap = new HashMap<>(); // only for encode
	
	public DefaultRpcCodec(ServiceMetas serviceMetas) {
		
		zlibTool = new ZlibTool();
		snappyTool = loadSnappy();  // continue if missing org.xerial.snappy dependence
		
		this.serviceMetas = serviceMetas;
		RpcData reqHeartBeatData = new RpcData(
				RpcMeta.newBuilder().setDirection(RpcMeta.Direction.REQUEST).setServiceId(1).setMsgId(1).build());
		ByteBuf bb = Unpooled.buffer(32);
		encode(reqHeartBeatData, bb);
		reqHeartBeatBytes = new byte[bb.readableBytes()];
		bb.readBytes(reqHeartBeatBytes);
		bb.clear();
		RpcData resHeartBeatData = new RpcData(
				RpcMeta.newBuilder().setDirection(RpcMeta.Direction.RESPONSE).setServiceId(1).setMsgId(1).build());
		encode(resHeartBeatData, bb);
		resHeartBeatBytes = new byte[bb.readableBytes()];
		bb.readBytes(resHeartBeatBytes);
		ReferenceCountUtil.release(bb);
	}

	public void configZip(int serviceId,int zip,int minSizeToZip) {
		zipMap.put(serviceId, zip);
		minSizeToZipMap.put(serviceId, minSizeToZip);
	}
	
	public RpcMeta decodeMeta(ByteBuf bb) {

		int len = bb.readableBytes();
		if (len < 8) {
			throw new RuntimeException("package_len_error");
		}

		int b0 = bb.readByte();
		int b1 = bb.readByte();
		int metaLen = bb.readShort();
		int packageLen = bb.readInt();

		if (b0 != 'K' && b1 != 'R') {
			throw new RuntimeException("package_flag_error");
		}
		if (metaLen <= 0 || metaLen > packageLen || packageLen + 8 != len) {
			throw new RuntimeException("package_len_not_match");
		}

		RpcMeta meta = null;
		try {
			ByteBuf metaBytes = bb.readSlice(metaLen);
			ByteBufInputStream is = new ByteBufInputStream(metaBytes);
			meta = RpcMeta.parseFrom(is);
		} catch (Exception e) {
			throw new RuntimeException("decode_meta_error");
		}
		if (meta.getDirection() == RpcMeta.Direction.INVALID_DIRECTION) {
			throw new RuntimeException("package_direction_error");
		}
		if (meta.getServiceId() < 1) {
			throw new RuntimeException("package_serviceId_error");
		}
		if (meta.getMsgId() < 1) {
			throw new RuntimeException("package_msgId_error");
		}

		return meta;
	}

	public RpcData decodeBody(RpcMeta meta, ByteBuf leftBuff) {
		int left = leftBuff.readableBytes();
		ByteBuf bodyBb = null;
		if( meta.getCompress() > 0 ) {
			byte[] bytes = new byte[left];
			leftBuff.readBytes(bytes);
			try {
				byte[] unzipBytes = unzip(meta.getCompress(),bytes);
				bodyBb = Unpooled.wrappedBuffer(unzipBytes);
			} catch (Exception e) {
				if (isRequest(meta))
					throw new RpcException(RetCodes.DECODE_REQ_ERROR, "decode request exception");
				else
					throw new RpcException(RetCodes.DECODE_RES_ERROR, "decode response exception");
			}				
		} else {
			if (left > 0) {
				bodyBb = leftBuff.readSlice(left);
			} else {
				if (!isRequest(meta)) {
					Message res = serviceMetas.generateRes(meta.getServiceId(), meta.getMsgId(),
							meta.getRetCode());
					return new RpcData(meta, res);
				}
			}
		}

		Method m = null;
		if (isRequest(meta)) {
			m = serviceMetas.findReqParser(meta.getServiceId(), meta.getMsgId());
		} else {
			m = serviceMetas.findResParser(meta.getServiceId(), meta.getMsgId());
		}
		
		if (m != null) {
			Message res = null;
			try {
				ByteBufInputStream is = new ByteBufInputStream(bodyBb == null ? Unpooled.EMPTY_BUFFER : bodyBb);
				res = (Message) m.invoke(null, is);
			} catch (Exception e) {
				if (isRequest(meta))
					throw new RpcException(RetCodes.DECODE_REQ_ERROR, "decode request exception");
				else
					throw new RpcException(RetCodes.DECODE_RES_ERROR, "decode response exception");
			}
			return new RpcData(meta, res);			
			
		} else {
			Descriptor desc = null;
			if (isRequest(meta)) {
				desc = serviceMetas.findDynamicReqDescriptor(meta.getServiceId(), meta.getMsgId());
			} else {
				desc = serviceMetas.findDynamicResDescriptor(meta.getServiceId(), meta.getMsgId());
			}	
			if (desc == null) {
				throw new RpcException(RetCodes.NOT_FOUND, "service_not_found");
			}
			
			DynamicMessage res = null;
			try {
				ByteBufInputStream is = new ByteBufInputStream(bodyBb == null ? Unpooled.EMPTY_BUFFER : bodyBb);
				res = DynamicMessage.parseFrom(desc, is); 
			} catch (Exception e) {
				if (isRequest(meta))
					throw new RpcException(RetCodes.DECODE_REQ_ERROR, "decode request exception");
				else
					throw new RpcException(RetCodes.DECODE_RES_ERROR, "decode response exception");
			}
			return new RpcData(meta, res);				
		}
	}

	public void encode(RpcData data, ByteBuf bb) {

		int bodyBytesLen = data.getBody() == null ? 0 : data.getBody().getSerializedSize();
		
		Integer zip = zipMap.get(data.getMeta().getServiceId());
		Integer minSizeToZip = minSizeToZipMap.get(data.getMeta().getServiceId());
		
		if( zip != null && zip.intValue() > 0 && minSizeToZip != null && bodyBytesLen > minSizeToZip.intValue() ) {

			try {
				byte[] body = data.getBody().toByteArray();
				byte[] encBody = zip(zip,body);
				ReflectionUtils.updateCompress(data.getMeta(),zip);
				
				int metaBytesLen = data.getMeta().getSerializedSize();
				int len = 8 + metaBytesLen + encBody.length;
				bb.capacity(len);

				ByteBufOutputStream os = new ByteBufOutputStream(bb);
				os.writeByte('K');
				os.writeByte('R');
				os.writeShort(metaBytesLen);
				os.writeInt(len-8);
				data.getMeta().writeTo(os);
				os.write(encBody);
			} catch (Exception e) {
				if (isRequest(data.getMeta()))
					throw new RpcException(RetCodes.ENCODE_REQ_ERROR, "encode request exception");
				else
					throw new RpcException(RetCodes.ENCODE_RES_ERROR, "encode response exception");
			}
			
			return;
		}
		
		int metaBytesLen = data.getMeta().getSerializedSize();
		int len = 8 + metaBytesLen + bodyBytesLen;
		bb.capacity(len);

		try {
			ByteBufOutputStream os = new ByteBufOutputStream(bb);
			os.writeByte('K');
			os.writeByte('R');
			os.writeShort(metaBytesLen);
			os.writeInt(len-8);
			data.getMeta().writeTo(os);
			if( data.getBody() != null ) {
				data.getBody().writeTo(os);
			}
		} catch (Exception e) {
			if (isRequest(data.getMeta()))
				throw new RpcException(RetCodes.ENCODE_REQ_ERROR, "encode request exception");
			else
				throw new RpcException(RetCodes.ENCODE_RES_ERROR, "encode response exception");
		}
	}

	public byte[] zip(int zipType, byte[] data) throws IOException {
		switch(zipType) {
			case GZIP: 
				return zlibTool.zip(data);
			case SNAPPY:
				if( snappyTool != null ) {
					return snappyTool.zip(data);
				} else {
					log.error("snappy not found, data not zipped");
					return data;
				}
			default:
				return data;
		}
	}
	
	public byte[] unzip(int zipType, byte[] data) throws IOException {
		switch(zipType) {
			case GZIP: 
				return zlibTool.unzip(data);
			case SNAPPY: 
				if( snappyTool != null ) {
					return snappyTool.unzip(data);
				} else {
					log.error("snappy not found, data not unzipped");
					return data;
				}
			default:
				return data;
		}
	}
	
	public void getReqHeartBeat(ByteBuf bb) {
		bb.writeBytes(reqHeartBeatBytes);
	}

	public void getResHeartBeat(ByteBuf bb) {
		bb.writeBytes(resHeartBeatBytes);
	}

	ZipUnzip loadSnappy() { 
		try {
			Class<?> cls = Class.forName("krpc.rpc.util.SnappyTool");
			return (ZipUnzip)cls.newInstance();
		} catch(Exception e) {
			log.error("snappy not loaded");
			return null;
		}
	}
		
	public boolean isRequest(RpcMeta meta) {
		return meta.getDirection() == RpcMeta.Direction.REQUEST;
	}
	
	public ServiceMetas getServiceMetas() {
		return serviceMetas;
	}

	public void setServiceMetas(ServiceMetas serviceMetas) {
		this.serviceMetas = serviceMetas;
	}

}