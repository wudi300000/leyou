package com.leyou.order.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.leyou.auth.entity.UserInfo;
import com.leyou.common.pojo.PageResult;
import com.leyou.item.pojo.Stock;
import com.leyou.order.client.GoodsClient;
import com.leyou.order.interceptor.LoginInterceptor;
import com.leyou.order.mapper.*;
import com.leyou.order.pojo.Order;
import com.leyou.order.pojo.OrderDetail;
import com.leyou.order.pojo.OrderStatus;
import com.leyou.order.pojo.SeckillOrder;
import com.leyou.order.service.OrderService;
import com.leyou.order.service.OrderStatusService;
import com.leyou.order.vo.OrderStatusMessage;
import com.leyou.utils.IdWorker;
import com.leyou.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import javax.sound.midi.SoundbankResource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Author: 98050
 * @Time: 2018-10-27 16:37
 * @Feature:
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private IdWorker idWorker;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderStatusMapper orderStatusMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private OrderStatusService orderStatusService;

    @Autowired
    private GoodsClient goodsClient;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Long createOrder(Order order) {
        //????????????
        //1.??????orderId
        long orderId = idWorker.nextId();
        //2.?????????????????????
        UserInfo userInfo = LoginInterceptor.getLoginUser();
        //3.???????????????
        order.setBuyerNick(userInfo.getUsername());
        order.setBuyerRate(false);
        order.setCreateTime(new Date());
        order.setOrderId(orderId);
        order.setUserId(userInfo.getId());
        //4.????????????
        this.orderMapper.insertSelective(order);

        //5.??????????????????
        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setOrderId(orderId);
        orderStatus.setCreateTime(order.getCreateTime());
        //???????????????????????????1
        orderStatus.setStatus(1);
        //6.????????????
        this.orderStatusMapper.insertSelective(orderStatus);

        //7.????????????????????????orderId
        order.getOrderDetails().forEach(orderDetail -> {
            //????????????
            orderDetail.setOrderId(orderId);
        });

        //8.?????????????????????????????????????????????
        this.orderDetailMapper.insertList(order.getOrderDetails());

        order.getOrderDetails().forEach(orderDetail -> this.stockMapper.reduceStock(orderDetail.getSkuId(), orderDetail.getNum()));

        return orderId;

    }

    /**
     * ???????????????????????????
     * @param id
     * @return
     */
    @Override
    public Order queryOrderById(Long id) {
        //1.????????????
        Order order = this.orderMapper.selectByPrimaryKey(id);
        //2.??????????????????
        Example example = new Example(OrderDetail.class);
        example.createCriteria().andEqualTo("orderId",id);
        List<OrderDetail> orderDetail = this.orderDetailMapper.selectByExample(example);
        orderDetail.forEach(System.out::println);
        //3.??????????????????
        OrderStatus orderStatus = this.orderStatusMapper.selectByPrimaryKey(order.getOrderId());
        //4.order????????????????????????
        order.setOrderDetails(orderDetail);
        //5.order????????????????????????
        order.setStatus(orderStatus.getStatus());
        //6.??????order
        return order;
    }

    /**
     * ??????????????????????????????????????????????????????????????????
     * @param page
     * @param rows
     * @param status
     * @return
     */
    @Override
    public PageResult<Order> queryUserOrderList(Integer page, Integer rows, Integer status) {
        try{
            //1.??????
            PageHelper.startPage(page,rows);
            //2.??????????????????
            UserInfo userInfo = LoginInterceptor.getLoginUser();
            //3.??????
            Page<Order> pageInfo = (Page<Order>) this.orderMapper.queryOrderList(userInfo.getId(), status);
            //4.??????orderDetail
            List<Order> orderList = pageInfo.getResult();
            orderList.forEach(order -> {
                Example example = new Example(OrderDetail.class);
                example.createCriteria().andEqualTo("orderId",order.getOrderId());
                List<OrderDetail> orderDetailList = this.orderDetailMapper.selectByExample(example);
                order.setOrderDetails(orderDetailList);
            });
            return new PageResult<>(pageInfo.getTotal(),(long)pageInfo.getPages(), orderList);
        }catch (Exception e){
            logger.error("??????????????????",e);
            return null;
        }
    }

    /**
     * ??????????????????
     * @param id
     * @param status
     * @return
     */
    @Override
    public Boolean updateOrderStatus(Long id, Integer status) {
        UserInfo userInfo = LoginInterceptor.getLoginUser();
        Long spuId = this.goodsClient.querySkuById(findSkuIdByOrderId(id)).getSpuId();

        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setOrderId(id);
        orderStatus.setStatus(status);

        //????????????
        OrderStatusMessage orderStatusMessage = new OrderStatusMessage(id,userInfo.getId(),userInfo.getUsername(),spuId,1);
        OrderStatusMessage orderStatusMessage2 = new OrderStatusMessage(id,userInfo.getId(),userInfo.getUsername(),spuId,2);
        //1.????????????????????????????????????
        switch (status){
            case 2:
                //2.????????????
                orderStatus.setPaymentTime(new Date());
                break;
            case 3:
                //3.????????????
                orderStatus.setConsignTime(new Date());
                //????????????????????????????????????????????????????????????
                orderStatusService.sendMessage(orderStatusMessage);
                orderStatusService.sendMessage(orderStatusMessage2);
                break;
            case 4:
                //4.???????????????????????????
                orderStatus.setEndTime(new Date());
                orderStatusService.sendMessage(orderStatusMessage2);
                break;
            case 5:
                //5.???????????????????????????
                orderStatus.setCloseTime(new Date());
                break;
            case 6:
                //6.????????????
                orderStatus.setCommentTime(new Date());
                break;

                default:
                    return null;
        }
        int count = this.orderStatusMapper.updateByPrimaryKeySelective(orderStatus);
        return count == 1;
    }

    /**
     * ???????????????????????????id
     * @param id
     * @return
     */
    @Override
    public List<Long> querySkuIdByOrderId(Long id) {
        Example example = new Example(OrderDetail.class);
        example.createCriteria().andEqualTo("orderId",id);
        List<OrderDetail> orderDetailList = this.orderDetailMapper.selectByExample(example);
        List<Long> ids = new ArrayList<>();
        orderDetailList.forEach(orderDetail -> ids.add(orderDetail.getSkuId()));
        return ids;
    }

    /**
     * ?????????????????????????????????
     * @param id
     * @return
     */
    @Override
    public OrderStatus queryOrderStatusById(Long id) {
        return this.orderStatusMapper.selectByPrimaryKey(id);
    }

    /**
     * ????????????????????????????????????????????????????????????Id
     * @param order
     * @return
     */
    @Override
    public List<Long> queryStock(Order order) {
        List<Long> skuId = new ArrayList<>();
        order.getOrderDetails().forEach(orderDetail -> {
            Stock stock = this.stockMapper.selectByPrimaryKey(orderDetail.getSkuId());
            if (stock.getStock() - orderDetail.getNum() < 0){
                //???????????????????????????
                skuId.add(orderDetail.getSkuId());
            }
        });
        return skuId;
    }

    /**
     * ????????????id?????????skuId
     * @param id
     * @return
     */
    public Long findSkuIdByOrderId(Long id){
        Example example = new Example(OrderDetail.class);
        example.createCriteria().andEqualTo("orderId", id);
        List<OrderDetail> orderDetail = this.orderDetailMapper.selectByExample(example);
        return orderDetail.get(0).getSkuId();
    }



}
