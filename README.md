# AURORA IA — projeto inicial Android

Este é um **protótipo inicial de código-fonte**, não um aplicativo final de produção. Foi preparado para abrir no Android Studio e servir como base para o desenvolvimento do AURORA IA no Samsung Galaxy A55 5G.

## O que esta primeira base faz
- Interface inicial em português.
- Acionamento por botão e reconhecimento de fala do Android, quando disponível.
- Aceita alguns comandos simples: abrir Waze, abrir WhatsApp, preparar uma mensagem para revisão e abrir o discador.
- Bloqueia a execução de comandos se o Android informar que o aparelho está bloqueado.
- Usa uma lista positiva de ações permitidas: não há ação para abrir aplicativos bancários.
- Mensagens são apenas preparadas para revisão; o app não envia mensagens automaticamente.
- Fala respostas usando Text-to-Speech, quando disponível.

## O que ainda NÃO está implementado
- IA generativa para revisão gramatical e detecção semântica de ambiguidades.
- Reconhecimento biométrico/robusto do locutor.
- Ativação contínua por palavra de ativação.
- Execução universal dentro de qualquer aplicativo.
- Verificação automática de todos os apps bancários instalados.
- APK compilado e testado em um aparelho real.

Esses recursos precisam de desenvolvimento e testes adicionais. A base atual não deve ser considerada uma solução de segurança certificada.

## Segurança e limites
- O AURORA IA não tenta desbloquear o aparelho.
- O aplicativo verifica o estado de bloqueio antes de executar comandos.
- O protótipo só oferece ações explicitamente programadas. Não existe um mecanismo genérico para abrir qualquer pacote arbitrário.
- O envio de mensagens não é automático; o usuário precisa revisar e concluir a ação no aplicativo escolhido.
- O Android pode restringir reconhecimento de fala, abertura de apps e execução em segundo plano conforme versão, permissões e configuração.

## Como abrir
1. Instale o Android Studio em um computador.
2. Abra a pasta `AURORA_IA_Android_Starter` como projeto.
3. Permita que o Gradle sincronize o projeto.
4. Conecte o Samsung Galaxy A55 por USB com depuração USB ativada, ou use um emulador.
5. Execute o app pelo Android Studio.

Requisitos configurados: Android Gradle Plugin 8.6.1, Gradle 8.7, JDK 17, compile/target SDK 35. O Android Studio pode pedir para instalar a plataforma Android 35.

## Próxima etapa recomendada
Implementar um módulo de revisão de mensagens com IA, usando uma API configurável e consentimento explícito antes de enviar qualquer texto. Para priorizar custo zero, a aplicação deve continuar funcionando nos comandos locais sem depender da API.
