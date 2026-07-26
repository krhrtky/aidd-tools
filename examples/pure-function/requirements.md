# Withdraw contract

`withdraw` is a total, pure function with no external I/O or mutable state.
It accepts `balance: Int` followed by `amount: Int` and returns `newBalance: Int`.
A successful call requires `balance >= 0`, `amount > 0`, and `balance >= amount`.
On success, `newBalance` equals `balance - amount`.
Return `InvalidBalance` when `balance < 0`.
Return `InvalidAmount` when `balance >= 0` and `amount <= 0`.
Return `InsufficientFunds` when `balance >= 0`, `amount > 0`, and `amount > balance`.
