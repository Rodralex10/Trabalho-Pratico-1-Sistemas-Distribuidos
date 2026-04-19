# Hangman Multiplayer

Neste Git encontra o Trabalho Prático 1 realizado por Rodrigo Lourenço e Rodrigo Talhas do curso de Engenharia Informática na cadeira de Sistemas Distribuidos.
Este trabalho prático corresponde ao Jogo da Forca Multijogador em Java com arquitetura cliente-servidor TCP/IP.

## Estrutura

```
hangman/
├── server/HangmanServer.java   # Servidor TCP multithreaded
├── client/HangmanClient.java   # Cliente com interface terminal
└── README.md
```

## Compilar

```bash
javac server/HangmanServer.java
javac client/HangmanClient.java
```

## Executar

```bash
# Servidor (porta default: 12345)
java server.HangmanServer
java server.HangmanServer 12345

# Cliente (default: localhost:12345)
java client.HangmanClient
java client.HangmanClient localhost 12345
```

## Como Jogar

1. Iniciar o servidor
2. Conectar 2-4 clientes (lobby de 20 segundos)
3. O jogo comeca automaticamente quando o lobby fecha
4. Em cada ronda, cada jogador envia um palpite:
   - **Uma letra** — revela posicoes se correta, senao perde 1 tentativa
   - **Palavra completa** — se correta, ganha; se errada, perde 1 tentativa
5. O jogo termina quando:
   - Um jogador adivinha a palavra (esse jogador ganha)
   - Todas as letras sao reveladas (todos os jogadores ganham)
   - As tentativas chegam a 0 (todos perdem)

## Protocolo

| Direcao | Mensagem | Descricao |
|---------|----------|-----------|
| S → C | `WELCOME <id> <total>` | Jogador aceite no lobby |
| S → C | `START <mask> <attempts> <timeout_ms>` | Jogo iniciado |
| S → C | `ROUND <k> <mask> <attempts> <used>` | Inicio de ronda |
| S → C | `STATE <mask> <attempts> <used>` | Resultado da ronda |
| S → C | `END WIN <winners> <word>` | Vitoria |
| S → C | `END LOSE <word>` | Derrota |
| S → C | `FULL` | Servidor cheio |
| C → S | `GUESS <text>` | Palpite do jogador |

## Constantes

| Parametro | Valor |
|-----------|-------|
| Porta | 12345 |
| Jogadores | 2-4 |
| Tentativas | 6 |
| Lobby timeout | 20s |
| Ronda timeout | 30s |
| Palavras | 100 (portugues) |

## Requisitos

- Java 11+
- Sem bibliotecas externas
