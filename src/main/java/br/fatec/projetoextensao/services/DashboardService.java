package br.fatec.projetoextensao.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.fatec.projetoextensao.dtos.DashboardResumoResponse;
import br.fatec.projetoextensao.dtos.EtapaFilaResponse;
import br.fatec.projetoextensao.dtos.GraficoResponse;
import br.fatec.projetoextensao.dtos.IndicadorRetrabalhoResponse;
import br.fatec.projetoextensao.dtos.SerieGraficoResponse;
import br.fatec.projetoextensao.entities.OrdemServicoEntity;
import br.fatec.projetoextensao.enums.StatusOsEnum;
import br.fatec.projetoextensao.exceptions.RecursoNaoEncontradoException;
import br.fatec.projetoextensao.repositories.CronogramaItemRepository;
import br.fatec.projetoextensao.repositories.CronogramaRepository;
import br.fatec.projetoextensao.repositories.ItemRotaProcessoRepository;
import br.fatec.projetoextensao.repositories.OrdemServicoRepository;
import br.fatec.projetoextensao.security.Perfis;
import lombok.RequiredArgsConstructor;

/**
 * INCLUÍDO (21/10/2026): dados do dashboard da tela inicial.
 *
 * resumo(): indicadores do topo da tela, calculados agora com os Services e repositories que já existem.
 *
 * grafico(nome): os gráficos ficam prontos na tela, mas os dados serão desenvolvidos depois (pedido de 21/10).
 * Enquanto isso, cada gráfico responde disponivel = false, e a tela mostra o quadro vazio com a mensagem.
 * Para ligar um gráfico, faça este método devolver, para o nome dele, o resultado de uma consulta (por exemplo, uma
 * view da Etapa 3 lida por um método @Query(nativeQuery = true) num repository; exemplo em docs/front-end.md),
 * preenchendo:
 * <ul>
 *   <li>rotulos: o eixo X (dias, meses, status ou funcionários);</li>
 *   <li>series: uma SerieGraficoResponse por linha/barra, com um valor por rótulo;</li>
 *   <li>disponivel = true e mensagem = null.</li>
 * </ul>
 * O front (js/paginas/dashboard.js) desenha qualquer resposta nesse formato, sem precisar mudar.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Gráficos programados na tela inicial: nome na URL -> título, tipo e sugestão de view para os dados. */
    private static final Map<String, String[]> GRAFICOS = new LinkedHashMap<>();

    static {
        GRAFICOS.put("producao-diaria", new String[] {"Produção diária", "barra", "vw_producao_diaria (etapas concluídas por dia)"});
        GRAFICOS.put("retrabalho-mensal", new String[] {"% de retrabalho por mês", "linha", "vw_retrabalho_mensal (RN-IRP-09)"});
        GRAFICOS.put("os-por-status", new String[] {"Ordens de serviço por status", "rosca", "vw_os_por_status"});
        GRAFICOS.put("etapas-por-funcionario", new String[] {"Etapas concluídas por funcionário", "barra-horizontal",
                "vw_produtividade_funcionario"});
    }

    private final OrdemServicoRepository ordemServicoRepository;
    private final ItemRotaProcessoRepository itemRotaProcessoRepository;
    private final CronogramaRepository cronogramaRepository;
    private final CronogramaItemRepository cronogramaItemRepository;
    private final StatusService statusService;
    private final ItemService itemService;
    private final ItemRotaProcessoService itemRotaProcessoService;
    private final TintaService tintaService;
    private final JdbcClient jdbcClient;

    /** Indicadores do topo do dashboard. */
    @PreAuthorize(Perfis.QUALQUER)
    @Transactional(readOnly = true)
    public DashboardResumoResponse resumo() {
        Map<String, Long> osPorStatus = new LinkedHashMap<>();
        for (StatusOsEnum s : StatusOsEnum.values()) {
            osPorStatus.put(s.getNome(), 0L);
        }
        long osAbertas = 0;
        for (OrdemServicoEntity os : ordemServicoRepository.findAll()) {
            StatusOsEnum s = statusService.statusAtual(os); // RN-HOS-03
            osPorStatus.merge(s.getNome(), 1L, Long::sum);
            if (!s.isEncerrado()) {
                osAbertas++;
            }
        }
        long atrasados = itemService.listarAtrasados().size(); // RN-ITE-22
        List<EtapaFilaResponse> fila = itemRotaProcessoService.listarFila();
        long emExecucao = fila.stream().filter(e -> e.dataInicio() != null).count();
        LocalDate hoje = LocalDate.now();
        long concluidasHoje = itemRotaProcessoRepository.countByDataConclusaoGreaterThanEqualAndDataConclusaoLessThan(
                hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay());
        IndicadorRetrabalhoResponse retrabalhoMes = itemRotaProcessoService.percentualRetrabalho(hoje.withDayOfMonth(1), hoje);
        int noCronograma = cronogramaRepository.findByDataPlanejada(hoje)
                .map(c -> cronogramaItemRepository.findByCronogramaOrderByPosicao(c).size())
                .orElse(0);
        long semEstoque = tintaService.listar().stream()
                .filter(t -> t.estoqueAtual() == null || t.estoqueAtual().compareTo(BigDecimal.ZERO) <= 0)
                .count();
        return new DashboardResumoResponse(osPorStatus, osAbertas, atrasados, emExecucao, fila.size() - emExecucao,
                concluidasHoje, retrabalhoMes, noCronograma, semEstoque);
    }

    /** Dados de um gráfico do dashboard (ver a explicação no início da classe). */
    @PreAuthorize(Perfis.QUALQUER)
    @Transactional(readOnly = true)
    public GraficoResponse grafico(String nome) {
        String[] g = GRAFICOS.get(nome);
        if (g == null) {
            throw new RecursoNaoEncontradoException("Gráfico não encontrado.");
        }
        Long viewsCriadas = jdbcClient
                .sql("select count(*) from INFORMATION_SCHEMA.VIEWS where TABLE_NAME in ('vw_painel_ordens_servico', 'vw_produtividade_funcionario', 'vw_retrabalho_mensal')")
                .query(Long.class)
                .single();
        if (viewsCriadas != null && viewsCriadas >= 3) {
            return switch (nome) {
                case "producao-diaria" -> consultarGrafico(nome, g, "Etapas concluídas",
                        "select convert(varchar(5), data_producao, 103) as rotulo, sum(etapas_concluidas) as valor from dbo.vw_produtividade_funcionario group by data_producao order by data_producao asc");
                case "retrabalho-mensal" -> consultarGrafico(nome, g, "% de retrabalho",
                        "select right('0' + cast(mes as varchar(2)), 2) + '/' + cast(ano as varchar(4)) as rotulo, percentual_retrabalho as valor from dbo.vw_retrabalho_mensal order by mes_referencia asc");
                case "os-por-status" -> consultarGrafico(nome, g, "OS",
                        "select status_atual as rotulo, count(*) as valor from dbo.vw_painel_ordens_servico group by status_atual order by valor desc");
                case "etapas-por-funcionario" -> consultarGrafico(nome, g, "Etapas concluídas",
                        "select funcionario_nome as rotulo, sum(etapas_concluidas) as valor from dbo.vw_produtividade_funcionario group by funcionario_nome order by valor desc");
                default -> new GraficoResponse(nome, g[0], g[1], List.of(), List.of(), false,
                        "Em desenvolvimento: os dados deste gráfico ainda não foram programados (sugestão: view " + g[2] + ").");
            };
        }
        return new GraficoResponse(nome, g[0], g[1], List.of(), List.of(), false,
                "Em desenvolvimento: os dados deste gráfico ainda não foram programados (sugestão: view " + g[2] + ").");
    }

    private GraficoResponse consultarGrafico(String nome, String[] g, String nomeSerie, String sql) {
        List<String> rotulos = new ArrayList<>();
        List<BigDecimal> valores = new ArrayList<>();
        jdbcClient.sql(sql).query((rs) -> {
            rotulos.add(rs.getString("rotulo"));
            valores.add(rs.getBigDecimal("valor"));
        });
        return new GraficoResponse(nome, g[0], g[1], rotulos, List.of(new SerieGraficoResponse(nomeSerie, valores)),
                true, null);
    }
}
