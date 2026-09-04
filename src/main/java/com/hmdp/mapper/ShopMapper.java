package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    /**
     * 单查 update_time（PK 查询）：缓存写回侧的版本核验用。
     * update_time 由 DB 的 ON UPDATE CURRENT_TIMESTAMP 自动维护，天然是行级版本号。
     * 只查一个字段而不是回读整行，把核验成本压到微秒级。
     */
    @Select("SELECT update_time FROM tb_shop WHERE id = #{id}")
    LocalDateTime selectUpdateTimeById(@Param("id") Long id);

}
