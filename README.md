# Nix nREPL server

A little helper to use nREPL with `nix repl`. To use it you need
`nix` on the path and then run this:

```
clojure -Sdeps '{:deps {deps-try {:git/url "https://github.com/frankitox/nix-nrepl" :sha "be61c319604cdf37b7a5f134717569fc8bae1c40"}}}' -m nix-nrepl.server
```

You'll see a message like

```
[NIX REPL] Welcome to Nix version 2.3.7. Type :? for help.


Starting on port 19993
Listening
```

At this point the nREPL server has started. Now you can use your
favorite editor to do REPL evaluation. Some editor/tool combinations:

- **Emacs with cider**: simply `:cider-connect` and provide the host
and port information.
- **Vim with iced**: TODO.
