SELECT saldo, valor, tipo, descricao, realizada_em
	FROM public.transacoes1;

insert into public.transacoes1 values (1000, 0, 'X', 'inicio', now())

insert into public.transacoes1
select (CASE tipo WHEN 'C' THEN saldo + 1 ELSE saldo - 1 END) saldo, 1, 'C', 'nova trans', now() FROM public.transacoes1 order by realizada_em desc limit 1 for update;

WITH statements AS (
SELECT * FROM pg_stat_statements pss
		JOIN pg_roles pr ON (userid=oid)
WHERE rolname = current_user
)
SELECT calls,
	mean_exec_time,
	query
FROM statements
ORDER BY calls DESC
LIMIT 50;


