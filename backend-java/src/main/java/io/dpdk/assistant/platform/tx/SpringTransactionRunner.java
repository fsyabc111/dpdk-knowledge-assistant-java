package io.dpdk.assistant.platform.tx;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TransactionRunner 默认实现：PROPAGATION_REQUIRED，参与者经
 * {@link DataSourceUtils#getConnection} 绑定到同一事务连接，异常整笔回滚。
 */
@Component
public class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;
    private final DataSource dataSource;

    public SpringTransactionRunner(TransactionTemplate template, DataSource dataSource) {
        this.template = template;
        this.dataSource = dataSource;
    }

    @Override
    public <T> T required(Function<DbTx, T> work) {
        return template.execute(status -> work.apply(() -> DataSourceUtils.getConnection(dataSource)));
    }
}
