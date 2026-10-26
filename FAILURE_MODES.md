

# POST /mpesa/v1/p2p/transfers


for the transfers to happen we need to accept sender, revicer and the amount that we are going to send.

{
 "from_wallet": "W001",
 "to_wallet": "W002",
 "amount": 10000,
 "reference": "rent-october"
}

    MAJOR possible failer that might happen:

    1. how do our service behaves in conncurrent operations
    2. Deadlock might occur
    3. reddundant operation might happen accidentally


    MAJOR possible Solution for this problem would be:
    1. using non blocking method like the one i am using ConncurrentHashMap()
        - to handle database conccurency issues we can use Atomicity and Transactional behaviour of PostgresSql.
            SQL like this:

            BEGIN

            select balance
            from accounts
            where id ='1'

            for update

            update accounts
            set balance = balance - amount

            COMMIT

            this make the transaction transaction which is pessimist locking method. only locking that users balance row 


    2. for the deadlock is going to happen when 2 user at the same time sends money to each other both will hold locks and wait each other so for that the solution to follow some standard way of locking. forexamp if we sort the id before locking we will have deadlock free transaction
        - this is instance level handling deadlock for database the postgresSql hadles it self.
    3. to handle reddundant operation that might happen we can use Idempotency-Key every time we will check if the key exists or not if it exists that mean it is reddudant but not its unique.


