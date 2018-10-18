package top.arkstack.shine.mq.annotation;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.arkstack.shine.mq.RabbitmqFactory;
import top.arkstack.shine.mq.bean.EventMessage;
import top.arkstack.shine.mq.constant.MqConstant;
import top.arkstack.shine.mq.coordinator.Coordinator;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 分布式事务 {@link top.arkstack.shine.mq.annotation.DistributedTrans} 切面
 *
 * @author 7le
 * @version 1.1.0
 */
@Slf4j
@Aspect
@Order(-99)
@Component
public class DistributedTransAspect {

    @Autowired
    ApplicationContext context;

    @Autowired
    RabbitmqFactory rabbitmqFactory;


    @Around(value = "@annotation(trans)")
    public void around(ProceedingJoinPoint pjp, DistributedTrans trans) throws Throwable {
        log.info("Start distributed transaction : {} ", trans);
        String exchange = trans.exchange();
        String routeKey = trans.routeKey();
        String coordinatorName = trans.coordinator();
        String msgId = trans.bizId() + MqConstant.SPLIT + getTime();

        Coordinator coordinator;
        try {
            coordinator = (Coordinator) context.getBean(coordinatorName);
        } catch (Exception e) {
            log.error("No coordinator or not joined the spring container : ", e);
            throw e;
        }

        //发送前暂存消息
        coordinator.setPrepare(msgId);
        Object data;
        try {
            data = pjp.proceed();
        } catch (Exception e) {
            log.error("Biz execution failed, id : {} :", msgId, e);
            //消息未发出 清理之前暂存的消息状态
            coordinator.delStatus(msgId);
            throw e;
        }
        if (data == null) {
            data = MqConstant.DATA_DEFAULT;
        }
        coordinator.setReady(msgId, new EventMessage(exchange, routeKey, null, data));
        try {
            rabbitmqFactory.setCorrelationData(msgId, coordinatorName);
            rabbitmqFactory.add(exchange, exchange, routeKey, null, null);
            rabbitmqFactory.getTemplate().send(exchange, data, routeKey);
        } catch (Exception e) {
            log.error("Message failed to be sent : ", e);
            //消息未发出 清理之前暂存的消息状态
            coordinator.delStatus(msgId);
            throw e;
        }

    }

    private static String getTime() {
        SimpleDateFormat df = new SimpleDateFormat(MqConstant.TIME);
        return df.format(new Date());
    }
}
