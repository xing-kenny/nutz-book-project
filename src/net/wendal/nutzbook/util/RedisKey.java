package net.wendal.nutzbook.util;


/**
 * 统一存在redis用到的key
 * @author wendal
 *
 */
public interface RedisKey {

	// 用户相关
	String RKEY_USER_ACCESSTOKEN = "u:accesstoken";
	String RKEY_USER_ACCESSTOKEN2 = "u:accesstoken2";
	String RKEY_USER_ACCESSTOKEN3 = "u:accesstoken3";
	String RKEY_USER_SCORE = "u:score";

	// 帖子相关
	String RKEY_TOPIC_VISIT = "t:visit";
	String RKEY_TOPIC_UPDATE = "t:update:";
	String RKEY_TOPIC_NOREPLY = "t:noreply";
	String RKEY_REPLY_LIKE = "t:like:";
	String RKEY_REPLY_COUNT = "t:reply:count";
	String RKEY_REPLY_LAST = "t:reply:last";
	
	String RKEY_TOPIC_SEXY = "t:sexy";
	
	String RKEY_U2F_REQ = "u2f:req";
	String RKEY_U2F_AUTH = "u2f:auth";
}
