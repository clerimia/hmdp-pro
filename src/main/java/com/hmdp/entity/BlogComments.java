package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_blog_comments")
public class BlogComments implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long blogId;

    /** 关联的 1 级评论 id，本身是一级评论时为 0 */
    private Long parentId;

    /** 回复的评论 id */
    private Long answerId;

    private String content;

    private Integer liked;

    /** 0：正常，1：被举报，2：禁止查看 */
    private Boolean status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
