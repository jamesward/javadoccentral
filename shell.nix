with import <nixpkgs> {};
mkShell {
  buildInputs = [
    pkgs.graalvm17-ce
  ];
}
