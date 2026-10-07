package com.acme.loja;

/** Código "do cliente" para os testes de rastro (fora do pacote do monitor). */
public final class Pedido {
    private Pedido() {
    }

    public static int fechar(int itens) {
        return calcular(itens);
    }

    static int calcular(int itens) {
        return 100 / itens;
    }

    public static void fecharComErroEncadeado() {
        try {
            fechar(0);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Falha ao fechar o pedido", e);
        }
    }
}
