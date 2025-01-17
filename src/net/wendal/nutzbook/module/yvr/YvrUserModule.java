package net.wendal.nutzbook.module.yvr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import net.wendal.nutzbook.bean.OAuthUser;
import net.wendal.nutzbook.bean.User;
import net.wendal.nutzbook.bean.UserProfile;
import net.wendal.nutzbook.module.BaseModule;
import net.wendal.nutzbook.service.UserService;
import net.wendal.nutzbook.util.Toolkit;

import org.apache.shiro.SecurityUtils;
import org.nutz.dao.Cnd;
import org.nutz.http.Http;
import org.nutz.http.Response;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.lang.Files;
import org.nutz.lang.Lang;
import org.nutz.lang.Strings;
import org.nutz.lang.meta.Email;
import org.nutz.lang.random.R;
import org.nutz.lang.util.NutMap;
import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.nutz.mvc.Scope;
import org.nutz.mvc.annotation.At;
import org.nutz.mvc.annotation.Attr;
import org.nutz.mvc.annotation.Fail;
import org.nutz.mvc.annotation.GET;
import org.nutz.mvc.annotation.Ok;
import org.nutz.mvc.annotation.POST;
import org.nutz.mvc.annotation.Param;
import org.nutz.mvc.view.HttpStatusView;
import org.nutz.trans.Atom;
import org.nutz.trans.Trans;

@At("/yvr/u")
@IocBean(create="init")
public class YvrUserModule extends BaseModule {

	private static final Log log = Logs.get();
	
	protected byte[] emailKEY = R.sg(24).next().getBytes();
	
	protected static HttpStatusView HTTP_304 = new HttpStatusView(304);
	
	protected Cache avatarCache;
	
	@Inject
	protected UserService userService;
	
	@At("/?")
	@Ok("beetl:yvr/user/user_index.btl")
	public Object userHome(String userName, @Attr(scope = Scope.SESSION, value = "me") int userId) {
		User user = dao.fetch(User.class, userName);
		if (user == null)
			return HttpStatusView.HTTP_404;
		UserProfile profile = fetch_userprofile(user.getId());
		if (profile == null)
			return HttpStatusView.HTTP_404;
		NutMap re = new NutMap();
		profile.setLoginname(userName);
		if (Strings.isBlank(profile.getDescription()))
			profile.setDescription(null);
		
		re.put("c_user", profile);
		if (userId > 0) {
			UserProfile me = fetch_userprofile(userId);
			me.setScore(yvrService.getUserScore(userId));
			re.put("current_user", me);
			// 显示accessToken二维码
			if (me.getUserId() == user.getId()) {
				re.put("access_token", yvrService.accessToken(me));
			}
		}
		re.put("recent_topics", yvrService.getRecentTopics(user.getId()));
		re.put("recent_replies", yvrService.getRecentReplyTopics(user.getId()));
		return re;
	}
	
	

	@Ok("raw:jpg")
	@At("/?/avatar")
	public Object userAvatar(String username, HttpServletRequest req, HttpServletResponse _resp){
		byte[] buf = null;
		Element ele = avatarCache.get(username);
		if (ele == null) {
			User user = dao.fetch(User.class, username);
			if (user != null) {
				dao.fetchLinks(user, "profile");
				if (user.getProfile() != null && user.getProfile().getAvatar() != null && user.getProfile().getAvatar().length > 0) {
					buf = user.getProfile().getAvatar();
				} 
				else {
					OAuthUser ouser = dao.fetch(OAuthUser.class, Cnd.where("userId", "=", user.getId()));
					if (ouser != null && ouser.getAvatar_url() != null) {
						try {
							Response resp = Http.get(ouser.getAvatar_url(), 5000);
							if (resp != null && resp.isOK()) {
								InputStream ins = resp.getStream();
								ByteArrayOutputStream out = new ByteArrayOutputStream();
								buf = new byte[resp.getHeader().getInt("Content-Length", 8192)];
								int len = 0;
								while (-1 != (len = ins.read(buf)))
									out.write(buf, 0, len);
								buf = out.toByteArray();
							}
						} catch (IOException e) {
							log.debug("load github avatar fail");
						}
					}
				}
			}
			if (buf == null) {
				buf = Files.readBytes(req.getServletContext().getRealPath("/rs/user_avatar/none.jpg"));
			}
		} else {
			buf = (byte[])ele.getObjectValue();
		}
		if (ele == null) {
			avatarCache.put(new Element(username, buf));
		}
		String etag = Lang.md5(new ByteArrayInputStream(buf));
		if (etag.equals(req.getHeader("If-None-Match"))) {
			return HTTP_304;
		}
		_resp.setHeader("ETag", etag);
		_resp.setHeader("Cache-Control", "max-age=86400");
		return buf;
	}
	
	/**
	 * 邮件回调的入口
	 * @param token 包含用户名和邮箱地址的加密内容
	 */
	@GET
	@At("/signup/?")
	@Ok("raw")
	public Object signup(String token) {
		try {
			token = Toolkit._3DES_decode(emailKEY, Toolkit.hexstr2bytearray(token));
			if (token == null) {
				return "非法token,请重新注册";
			}
		} catch (Exception e) {
			return "非法token,请重新注册";
		}
		final String[] tmps = token.split(",");
		long time = Long.parseLong(tmps[0]);
		if ((System.currentTimeMillis()/1000) - time > 24*60*60) {
			return "该链接已经过期";
		}
		// 再次检查用户名
		if (0 != dao.count(User.class, Cnd.where("name", "=", tmps[1]))) {
			return "用户名已被占用";
		}
		if (0 != dao.count(UserProfile.class, Cnd.where("email", "=", tmps[2]))) {
			return "Email地址已被占用";
		}
		Trans.exec(new Atom(){
			public void run() {
				User user = userService.add(tmps[1], tmps[3]);
				UserProfile profile = dao.fetch(UserProfile.class, user.getId());
				profile.setEmail(tmps[2]);
				profile.setEmailChecked(true);
				profile.setNickname(tmps[1]);
				profile.setUser(user);
				profile.setUserId(user.getId());
				dao.update(profile);
			}
		});
		return "注册成功,可以登陆了";
	}

	protected static Pattern P_USERNAME = Pattern.compile("^[a-z][a-z0-9]{4,10}$");
	
	@POST
	@At
	@Ok("json")
	public Object signup(@Param("email")String email, 
						@Param("username")String username,
						@Param("password")String password,
			HttpServletRequest req) {
		if (Strings.isBlank(password) || password.length()<8) {
			return ajaxFail("密码强度不够!!");
		}
		if (Strings.isBlank(username) || !P_USERNAME.matcher(username.toLowerCase()).find()) {
			return ajaxFail("用户名不合法");
		}
		int count = dao.count(User.class, Cnd.where("name", "=", username));
		if (count != 0) {
			return ajaxFail("用户名已经存在");
		}
		if (email.contains(",")||password.contains(",")) {
			return ajaxFail("不允许使用英文逗号");
		}
		try {
			new Email(email);
		} catch (Exception e) {
			return ajaxFail("Email地址不合法");
		}
		count = dao.count(UserProfile.class, Cnd.where("email", "=", email));
		if (count != 0) {
			return ajaxFail("Email已经存在");
		}
		try {
			String token = String.format("%s,%s,%s,%s", System.currentTimeMillis()/1000, username, email, password);
			token = Toolkit._3DES_encode(emailKEY, token.getBytes());
			String url = req.getRequestURL() + "/" + token;
			String html = "<div>如果无法点击,请拷贝一下链接到浏览器中打开<p/>注册链接 %s</div>";
			html = String.format(html, url, url);
			if (emailService.send(email, "Nutz社区注册邮件 -- " + username, html))
				return ajaxOk("请查收邮件,点击邮件中的链接即可完成注册");
		} catch (Exception e) {
			log.debug("有点问题", e);
		}
		return ajaxOk("发送邮件失败");
	}
	
	@At("/description")
	@Ok("json")
	@POST
	public Object updateUserDt(@Attr(scope = Scope.SESSION, value = "me") int userId,@Param("update_value")String original_value,@Param("update_value")String update_value){
		UserProfile profile = fetch_userprofile(userId);
		if (profile != null) {
			profile.setDescription(update_value);
			dao.update(profile, "description");
		}else{
			return original_value;
		}
		return profile.getDescription();
	}
	
	@At("/oauth/github")
	@Ok("->:/oauth/github")
	public void oauth(String type, HttpServletRequest req, HttpSession session){
		String url = req.getHeader("Rerefer");
		if (url == null)
			url = "/yvr/list";
		session.setAttribute("oauth.return.url", url);
	}

	@At
	@Ok(">>:/yvr/list")
	@Fail(">>:/yvr/list")
	public void logout() {
		SecurityUtils.getSubject().logout();
	}
	
	public void init() {
		avatarCache = cacheManager.getCache("yvr_avatar");
		if (avatarCache == null) {
			cacheManager.addCache("yvr_avatar");
			avatarCache = cacheManager.getCache("yvr_avatar");
		}
	}
}
