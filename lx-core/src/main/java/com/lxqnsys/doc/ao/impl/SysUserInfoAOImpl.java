package com.lxqnsys.doc.ao.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.laxqnsys.common.enums.ErrorCodeEnum;
import com.laxqnsys.common.exception.BusinessException;
import com.laxqnsys.common.util.AESUtil;
import com.laxqnsys.common.util.RedissonLock;
import com.lxqnsys.doc.ao.SysUserInfoAO;
import com.lxqnsys.doc.constants.CommonCons;
import com.lxqnsys.doc.constants.RedissonLockPrefixCons;
import com.lxqnsys.doc.context.LoginContext;
import com.lxqnsys.doc.dao.entity.SysUserInfo;
import com.lxqnsys.doc.enums.UserStatusEnum;
import com.lxqnsys.doc.model.bo.UserInfoBO;
import com.lxqnsys.doc.model.vo.UserInfoUpdateVO;
import com.lxqnsys.doc.model.vo.UserInfoVO;
import com.lxqnsys.doc.model.vo.UserLoginVO;
import com.lxqnsys.doc.model.vo.UserPwdModifyVO;
import com.lxqnsys.doc.model.vo.UserRegisterVO;
import com.lxqnsys.doc.service.ISysUserInfoService;
import com.lxqnsys.doc.util.WebUtil;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @author wuzhenhong
 * @date 2024/5/14 11:07
 */
@Service
public class SysUserInfoAOImpl implements SysUserInfoAO {

    @Autowired
    private ISysUserInfoService sysUserInfoService;

    @Autowired
    private RedissonLock redissonLock;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void register(UserRegisterVO userRegisterVO) {

        long count = sysUserInfoService.count(Wrappers.<SysUserInfo>lambdaQuery()
            .eq(SysUserInfo::getAccount, userRegisterVO.getAccount()));
        if (count > 0L) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "名为%s的账户已存在，请设置其他的账户名！");
        }

        String lockKey = RedissonLockPrefixCons.USER_REGISTER + "_" + userRegisterVO.getAccount();
        redissonLock.tryLock(lockKey, 1, TimeUnit.SECONDS, () -> {
            long c = sysUserInfoService.count(Wrappers.<SysUserInfo>lambdaQuery()
                .eq(SysUserInfo::getAccount, userRegisterVO.getAccount()));
            if (c > 0L) {
                throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "名为%s的账户已存在，请设置其他的账户名！");
            }
            SysUserInfo userInfo = new SysUserInfo();
            userInfo.setAccount(userRegisterVO.getAccount());
            String pwd = AESUtil.encrypt(userRegisterVO.getPassword(), CommonCons.AES_KEY);
            userInfo.setPassword(pwd);
            userInfo.setCreateAt(LocalDateTime.now());
            userInfo.setVersion(0);
            userInfo.setUpdateAt(LocalDateTime.now());
            userInfo.setStatus(UserStatusEnum.NORMAL.getStatus());
            sysUserInfoService.save(userInfo);
        });

    }

    @Override
    public void login(UserLoginVO userLoginVO, HttpServletResponse response) {

        String password = userLoginVO.getPassword();
        String pwd = AESUtil.encrypt(password, CommonCons.AES_KEY);
        SysUserInfo userInfo = sysUserInfoService.getOne(Wrappers.<SysUserInfo>lambdaQuery()
            .eq(SysUserInfo::getAccount, userLoginVO.getAccount())
            .eq(SysUserInfo::getPassword, pwd));
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "用户名或者密码错误！");
        }

        this.userStatusCheck(userInfo);

        // 踢人
        String key = this.downOldLogin(userInfo.getId());

        String token = UUID.randomUUID().toString().replace("-", "");
        UserInfoBO userInfoBO = new UserInfoBO();
        userInfoBO.setAccount(userInfo.getAccount());
        userInfoBO.setId(userInfo.getId());
        stringRedisTemplate.opsForValue()
            .set(token, JSONUtil.toJsonStr(userInfoBO), CommonCons.LOGIN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        stringRedisTemplate.opsForValue().set(key, token, CommonCons.LOGIN_TOKEN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        WebUtil.saveCookie(response, token, CommonCons.LOGIN_EXPIRE_SECONDS);
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = WebUtil.getCookie(request, CommonCons.TOKEN_KEY);
        if (!StringUtils.hasText(token)) {
            return;
        }
        WebUtil.saveCookie(response, token, 0);
        stringRedisTemplate.delete(token);
        String key = CommonCons.LOGIN_USER_TOKE_KEY + LoginContext.getUserId();
        stringRedisTemplate.delete(key);
    }

    @Override
    public UserInfoVO getUserInfo() {
        // 获取当前登录人信息
        Long id = LoginContext.getUserId();
        if (Objects.isNull(id)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "获取当前登录人信息失败！");
        }
        SysUserInfo userInfo = sysUserInfoService.getById(id);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                String.format("未获取到id为%s的登录人信息！", id));
        }

        this.userStatusCheck(userInfo);

        UserInfoVO userInfoVO = new UserInfoVO();
        userInfoVO.setId(userInfo.getId());
        userInfoVO.setAccount(userInfo.getAccount());
        userInfoVO.setUserName(userInfo.getUserName());
        userInfoVO.setAvatar(userInfo.getAvatar());
        userInfoVO.setCreateAt(CommonCons.YYYY_MM_SS_HH_MM_SS.format(userInfo.getCreateAt()));
        return userInfoVO;
    }

    @Override
    public void updateUserInfo(UserInfoUpdateVO userInfoUpdateVO) {
        Long userId = LoginContext.getUserId();
        SysUserInfo userInfo = sysUserInfoService.getById(userId);
        if (Objects.isNull(userInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                String.format("未获取到id为%s的登录人信息！", userId));
        }
        this.userStatusCheck(userInfo);
        SysUserInfo update = new SysUserInfo();
        update.setId(userId);
        update.setUserName(userInfoUpdateVO.getUserName());
        update.setAvatar(userInfoUpdateVO.getAvatar());
        sysUserInfoService.updateById(update);
    }

    @Override
    public void changePassword(UserPwdModifyVO userPwdModifyVO) {
        Long userId = LoginContext.getUserId();
        SysUserInfo sysUserInfo = sysUserInfoService.getById(userId);
        if(Objects.isNull(sysUserInfo)) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(),
                String.format("未获取到id为%s的登录人信息！", userId));
        }
        String oldPassword = userPwdModifyVO.getOldPassword();
        String password = sysUserInfo.getPassword();
        if(!password.equals(AESUtil.encrypt(oldPassword, CommonCons.AES_KEY))) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "原秘密输入不正确！");
        }
        String newPassword = userPwdModifyVO.getNewPassword();
        String newPwd = AESUtil.encrypt(newPassword, CommonCons.AES_KEY);
        SysUserInfo update = new SysUserInfo();
        update.setId(userId);
        update.setPassword(newPwd);
        sysUserInfoService.updateById(update);
    }

    private String downOldLogin(Long userId) {

        String key = CommonCons.LOGIN_USER_TOKE_KEY + userId;
        String oldToken = stringRedisTemplate.opsForValue().get(key);
        if(StringUtils.hasText(oldToken)) {
            // 踢掉其他的登录信息
            stringRedisTemplate.delete(oldToken);
        }
        return key;
    }

    private void userStatusCheck(SysUserInfo userInfo) {

        if (UserStatusEnum.DISABLED.getStatus().equals(userInfo.getStatus())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "当前用户已被禁用！");
        }

        if (UserStatusEnum.DELETE.getStatus().equals(userInfo.getStatus())) {
            throw new BusinessException(ErrorCodeEnum.ERROR.getCode(), "当前用户已注销！");
        }
    }
}
