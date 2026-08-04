package com.algaworks.algashop.billingscheduler.infrastructure;

import com.algaworks.algashop.billingscheduler.application.CancelExpiredInvoicesApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CancelExpiredInvoicesApplicationServiceJDBCImpl implements CancelExpiredInvoicesApplicationService {

    // Abstração do Spring JDBC para executar queries SQL sem precisar gerenciar conexões manualmente
    private final JdbcOperations jdbcOperations;
    // Permite controlar transações programaticamente, garantindo que SELECT + UPDATE rodem na mesma transação
    private final TransactionTemplate transactionTemplate;

    // chama o api client de fastpay
    private final FastpayPaymentAPIClient fastpayPaymentAPIClient;

    // Define o período de tolerância: uma fatura é considerada expirada após 1 dia do vencimento
    private static final Duration EXPIRED_SINCE = Duration.ofDays(1);

    // Limita a quantidade de faturas processadas por ciclo de execução, evitando sobrecarga no banco
    private static final int BATCH_LIMIT = 50;

    // Constantes de status/razão para evitar "magic strings" espalhadas no código
    private static final String UNPAID_STATUS = "UNPAID";       // status de faturas ainda não pagas
    private static final String CANCELED_STATUS = "CANCELED";   // status aplicado após o cancelamento
    private static final String CANCELED_REASON = "Invoice expired"; // motivo registrado ao cancelar

    // SQL que seleciona os IDs das faturas com vencimento já expirado e que ainda estão com status UNPAID.
    // O intervalo de dias é interpolado via String.format a partir da constante EXPIRED_SINCE.
    // 'LIMIT ?' restringe ao tamanho do lote (BATCH_LIMIT).
    // 'FOR UPDATE' trava as linhas selecionadas dentro da transação, impedindo que outra instância as modifique.
    // 'SKIP LOCKED' ignora linhas já travadas por outra transação, permitindo processamento concorrente
    // entre múltiplas instâncias do scheduler sem bloqueio mútuo.
    private static final String SELECT_QUERY_EXPIRED_INVOICES_SQL = String.format("""
            select i.id, ps.gateway_code
            from invoice i
            inner join payment_settings ps on i.payment_settings_id = ps.id
            where i.expires_at <= now() - interval '%d days'
            and i.status = ?
            order by i.expires_at asc
            limit ?
            for update
            skip locked
    """, EXPIRED_SINCE.toDays());

    // SQL de atualização que marca a fatura como cancelada, registrando data e motivo do cancelamento
    private static final String UPDATE_INVOICES_STATUS_SQL = """
            update invoice set status = ?, canceled_at = now(), cancel_reason = ?
            where id = ?
    """;

    // Ponto de entrada da regra: executa busca e cancelamento dentro de uma transação programática.
    // O transactionTemplate garante que o SELECT FOR UPDATE e os UPDATEs compartilhem a mesma transação,
    // mantendo os locks adquiridos até o commit/rollback.
    @Override
    public void cancelExpiredInvoices() {
        transactionTemplate.execute(status -> {
            List<InvoiceProjection> invoicesIds = fetchExpiredInvoices();
            log.info("Tasks - Total invoices fetched: {}", invoicesIds.size());

            // Se não houver faturas expiradas, encerra a transação sem executar UPDATEs desnecessários
            if (invoicesIds.isEmpty()) {
                log.info("Tasks - No expired invoices was found for cancellation");
                return true;
            }

            int totalCanceledInvoices = cancelInvoices(invoicesIds);
            log.info("Tasks - Total invoices canceled: {}", totalCanceledInvoices);

            return true;
        });
    }

    // Executa a query de seleção, retornando uma lista de UUIDs das faturas elegíveis para cancelamento
    private List<InvoiceProjection> fetchExpiredInvoices() {
        // PreparedStatementSetter: define os parâmetros do PreparedStatement de forma segura (evita SQL injection).
        // 1º '?' → status UNPAID (filtra apenas faturas não pagas)
        // 2º '?' → BATCH_LIMIT (limita a quantidade de linhas retornadas por ciclo)
        PreparedStatementSetter preparedStatementSetter = ps -> {
            ps.setString(1, UNPAID_STATUS);
            ps.setInt(2, BATCH_LIMIT);
        };

        // RowMapper: converte cada linha do ResultSet em um objeto Java — neste caso, extrai a coluna "id" como UUID
        RowMapper<InvoiceProjection> mapper = (resultSet, rowNum) -> new InvoiceProjection(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("gateway_code")
        );

        // Executa a query passando o setter de parâmetros e o mapper de linhas, retornando List<UUID>
        return jdbcOperations.query(SELECT_QUERY_EXPIRED_INVOICES_SQL, preparedStatementSetter, mapper);
    }

    // Cancela todas as faturas do lote de uma vez usando batchUpdate, mais eficiente que updates individuais
    // pois envia todos os comandos ao banco em uma única ida (round-trip)
    private int cancelInvoices(List<InvoiceProjection> invoices) {
        // lista dos invoices cancelados para ter integridade de dados ao chamar api client
        // caso falhe não será persistido no banco, assim da proxima vez poderá ter outra tentativa de cancelamento
        List<InvoiceProjection> invoicesCanceled = invoices.stream().filter(invoiceProjection -> {
            try {
                fastpayPaymentAPIClient.cancel(invoiceProjection.getPaymentGatewayCode());
                log.info("Task - Invoice {} has the payment {} cancelled on gateway", invoiceProjection.getId(), invoiceProjection.getPaymentGatewayCode());
                return true;
            } catch (Exception e) {
                log.error("Task - Failed to cancel invoice {} payment {} on the gateway", invoiceProjection.getId(), invoiceProjection.getPaymentGatewayCode(), e);
                return false;
            }
        }).toList();

        try {
           // batchUpdate: executa o mesmo SQL para cada ID do lote de forma agrupada.
           // O ParameterizedPreparedStatementSetter define os parâmetros na ordem dos '?' da query:
           // 1) novo status (CANCELED), 2) motivo do cancelamento, 3) ID da fatura
           jdbcOperations.batchUpdate(UPDATE_INVOICES_STATUS_SQL,
                   invoicesCanceled,
                   invoicesCanceled.size(),
                   (ps, invoiceProjection) -> {
                        ps.setString(1, CANCELED_STATUS);
                        ps.setString(2, CANCELED_REASON);
                        ps.setObject(3, invoiceProjection.getId());
                   }
           );
           // invoicesCanceled, nao invoices: so entram aqui as faturas cujo cancelamento
           // o gateway aceitou. Logar a lista original faria este log anunciar faturas
           // que nao foram canceladas, contradizendo o total logo abaixo.
           log.info("Invoice canceled IDs {}", invoicesCanceled);
           return invoicesCanceled.size();
        } catch (DataAccessException e) {
           // Captura falha no batch inteiro — se o batchUpdate falhar, nenhuma fatura do lote é cancelada
           // (o rollback da transação garante atomicidade)
           log.error("Task - Failed to cancel invoices", e);
           return 0;
        }
    }
}
