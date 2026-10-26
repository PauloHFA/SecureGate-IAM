package com.securegate.e2e;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Contract and governance test for Stage 12C: validates the final architecture diagrams, roadmap
 * closure report, ADR-073, evidence, and 100% completion of the implementation plan checklist.
 */
class FinalMaterialsClosureIT {

  @Test
  void validatesArchitectureOverviewAndMermaidDiagrams() throws Exception {
    Path archPath =
        Path.of("../docs/arquitetura/visao-geral-arquitetura.md").toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(archPath), "visao-geral-arquitetura.md must exist");

    String content = Files.readString(archPath);
    assertTrue(content.contains("flowchart TD"), "Must contain C4 Context flowchart");
    assertTrue(content.contains("flowchart LR"), "Must contain C4 Container flowchart");
    assertTrue(content.contains("sequenceDiagram"), "Must contain sequence diagram");
    assertTrue(content.contains("API Gateway"), "Must describe API Gateway");
    assertTrue(content.contains("IAM Core"), "Must describe IAM Core");
    assertTrue(content.contains("Policy Enforcer"), "Must describe Policy Enforcer");
    assertTrue(content.contains("HashiCorp Vault"), "Must describe HashiCorp Vault");
    assertTrue(content.contains("Fail-Closed"), "Must document fail-closed posture");
  }

  @Test
  void validatesFinalClosureReport() throws Exception {
    Path reportPath =
        Path.of("../docs/relatorios/encerramento-roadmap-final.md").toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(reportPath), "encerramento-roadmap-final.md must exist");

    String report = Files.readString(reportPath);
    assertTrue(
        report.contains("100% CONCLUÍDO E HOMOLOGADO"), "Must declare 100% roadmap completion");
    assertTrue(report.contains("Matriz de Rastreabilidade"), "Must contain traceability matrix");
    assertTrue(
        report.contains("Registro da Demonstração Ponta a Ponta"),
        "Must contain demonstration transcript");
    assertTrue(
        report.contains("Comparativo de Indicadores e Metas do Roadmap"),
        "Must contain metrics summary");
  }

  @Test
  void validatesAdr073RoadmapClosure() throws Exception {
    Path adrPath =
        Path.of("../docs/adr/073-encerramento-do-roadmap-e-materiais-finais.md")
            .toAbsolutePath()
            .normalize();
    assertTrue(
        Files.isRegularFile(adrPath),
        "073-encerramento-do-roadmap-e-materiais-finais.md must exist");

    String adr = Files.readString(adrPath);
    assertTrue(adr.contains("Status:** Aceito"), "ADR-073 must be accepted");
    assertTrue(adr.contains("Etapa 12C"), "Must reference Stage 12C");
    assertTrue(
        adr.contains("visao-geral-arquitetura.md"), "Must reference architecture overview file");
  }

  @Test
  void validatesImplementationPlanFullCompletion() throws Exception {
    Path planPath = Path.of("../docs/PLANO-DE-IMPLEMENTACAO.md").toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(planPath), "PLANO-DE-IMPLEMENTACAO.md must exist");

    String plan = Files.readString(planPath);
    // Stage 12 must have all items checked:
    assertTrue(
        plan.contains(
            "- [x] Completar diagramas, ADRs, coleção Bruno/Postman e gravação da demonstração."),
        "All Stage 12 checklist items must be checked");
  }
}
