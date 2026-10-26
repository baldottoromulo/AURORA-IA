# AURORA IA — como obter o APK sem instalar Android Studio

Este pacote inclui um fluxo opcional para compilar o projeto na nuvem usando GitHub Actions. Você precisará de uma conta GitHub e de um navegador. O processo pode mudar conforme a interface do GitHub.

## Passo a passo

1. Entre no GitHub e crie uma conta, se ainda não tiver.
2. Crie um repositório novo. Pode chamá-lo `aurora-ia-android`. Para evitar expor o projeto, escolha **Private**.
3. No repositório, escolha a opção de adicionar/carregar arquivos e envie **o conteúdo descompactado** desta pasta. É importante que `settings.gradle.kts`, `build.gradle.kts`, a pasta `app` e a pasta `.github` fiquem na raiz do repositório — não coloque tudo dentro de uma segunda pasta.
4. Abra a aba **Actions** do repositório. Se o GitHub pedir para habilitar Actions, habilite.
5. O fluxo “Construir APK do AURORA IA” deve iniciar após o envio para a branch `main`. Se não iniciar, entre em Actions, selecione esse fluxo e use **Run workflow**.
6. Aguarde a execução terminar. Se ficar verde, abra a execução e encontre a seção **Artifacts**.
7. Baixe `AURORA-IA-debug-apk.zip` e extraia o arquivo `app-debug.apk`.
8. Transfira o APK para o Samsung Galaxy A55 e toque nele para instalar. O Android poderá pedir autorização para instalar aplicativos dessa origem. Só prossiga se você tiver conferido que o APK veio da execução do seu repositório privado.

## Limitações importantes

- O APK é uma compilação de desenvolvimento, não um app publicado nem auditado.
- A primeira versão é um protótipo: não inclui ainda IA generativa de revisão de mensagens, verificação biométrica da voz, palavra de ativação contínua ou automação universal.
- O reconhecimento de fala depende dos serviços de voz disponíveis no aparelho.
- A função “preparar mensagem” mostra o texto para revisão e abre o menu de compartilhamento; não envia automaticamente.
- A restrição bancária desta base significa que não há comando programado para abrir bancos. Ela não controla o sistema inteiro nem impede que você abra bancos manualmente.
- A checagem de bloqueio da tela é inicial e precisa de testes no aparelho; não considere esta versão uma proteção certificada para roubo/perda.
- Nunca forneça senhas, dados bancários ou chaves de API em arquivos públicos. Não publique o repositório publicamente.

## Se você preferir ajuda presencial/remota

Se esses passos forem complicados, peça a alguém de confiança com experiência em computador para ajudar a criar o repositório privado e executar o fluxo. Você não precisa instalar Android Studio no notebook para usar este caminho.
