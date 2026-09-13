package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    private String city;

    /** 不超过 128 个字符 */
    private String introduce;

    private Integer fans;

    /** 关注的人的数量 */
    private Integer followee;

    /** 0：男，1：女 */
    private Boolean gender;

    private LocalDate birthday;

    private Integer credits;

    /** 0~9 级，0 代表未开通会员 */
    private Boolean level;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
