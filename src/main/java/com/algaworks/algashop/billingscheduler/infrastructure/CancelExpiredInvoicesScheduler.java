package com.algaworks.algashop.billingscheduler.infrastructure;

import com.algaworks.algashop.billingscheduler.application.CancelExpiredInvoicesApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Short-lived service: a aplicação sobe, executa a tarefa e encerra automaticamente.
// Diferente do @Scheduled (que mantém a aplicação viva rodando periodicamente),
// aqui o ciclo de vida é controlado externamente (ex: CronJob do Kubernetes),
// que reinicia o container no intervalo desejado.
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelExpiredInvoicesScheduler implements ApplicationRunner {

    private final CancelExpiredInvoicesApplicationService applicationService;

    //    @Scheduled(fixedRate = 5000) // cada 5 segundos
    //    public void runTasks() {
    //        log.info("Task started - canceled expired invoices");
    //        applicationService.cancelExpiredInvoices();
    //        log.info("Task finished - expired invoices");
    //      }

    // ApplicationRunner: o Spring invoca run() logo após o contexto ser inicializado.
    // Como não há servidor web embutido (Tomcat excluído no build.gradle),
    // a aplicação encerra naturalmente assim que run() termina,
    // liberando recursos (conexões, memória) imediatamente após a execução.
    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Task started - canceled expired invoices");
        applicationService.cancelExpiredInvoices();
        log.info("Task finished - expired invoices");
    }
}
